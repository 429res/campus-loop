package edu.campusloop;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.campusloop.exchange.ExchangeCreationCommand;
import edu.campusloop.exchange.ExchangeLifecycleRules.HandoffKind;
import edu.campusloop.web.exchange.service.*;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

@SpringBootTest
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles(resolver=CampusIntegrationTest.Profile.class)
@Import(AdminExchangeIntegrationTest.ProbeConfiguration.class)
class AdminExchangeIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        boolean mysql=Boolean.getBoolean("campus.mysql-test");
        String url=mysql?System.getenv("TEST_DB_URL"):
            "jdbc:h2:mem:campus_loop_admin_exchange_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        if(mysql && (url==null || !url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/campus_loop_[a-z0-9_]*test(?:\\?.*)?")))
            throw new IllegalStateException("Admin exchange tests require an isolated localhost campus_loop_*test schema");
        String username=mysql?System.getenv("TEST_DB_USERNAME"):"sa", password=mysql?System.getenv("TEST_DB_PASSWORD"):"";
        if(username==null || password==null) throw new IllegalStateException("Explicit test database credentials required");
        registry.add("spring.datasource.url",()->url);registry.add("spring.datasource.username",()->username);registry.add("spring.datasource.password",()->password);
        registry.add("spring.datasource.driver-class-name",()->mysql?"com.mysql.cj.jdbc.Driver":"org.h2.Driver");
        registry.add("spring.flyway.url",()->url);registry.add("spring.flyway.user",()->username);registry.add("spring.flyway.password",()->password);
        registry.add("campus.bootstrap-enabled",()->false);registry.add("campus.exchange-expiry.enabled",()->false);
        registry.add("campus.registration-mode",()->"DEVELOPMENT_SELF_SERVICE");
        registry.add("campus.jwt-secret",()->UUID.randomUUID().toString()+UUID.randomUUID());
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExchangeCreationTransaction creation;
    @Autowired ExchangeLifecycleService lifecycle;
    @Autowired ExchangeDatabaseClock clock;
    private final List<Long> users=new ArrayList<>();
    private Account admin,a,b,c;
    private Map<String,List<Map<String,Object>>> baseline;
    private record Account(long id,String token) {}
    private record Ring(long id,List<Long> items) {}

    @BeforeEach void fixtures() throws Exception {
        baseline=businessRows();admin=account(true);a=account(false);b=account(false);c=account(false);
    }
    @AfterEach void cleanup() {
        ReadProbe.before.remove();
        for(long user:users) for(long exchange:jdbc.queryForList("SELECT id FROM cl_exchange WHERE initiator_id=?",Long.class,user)) {
            for(String table:List.of("cl_exchange_resolution","cl_item_history","cl_item_hold","cl_exchange_event","cl_exchange_demand","cl_exchange_participant"))
                jdbc.update("DELETE FROM "+table+" WHERE exchange_id=?",exchange);
            jdbc.update("DELETE FROM cl_exchange WHERE id=?",exchange);
        }
        for(long user:users) {
            jdbc.update("DELETE FROM cl_demand_item WHERE demand_id IN (SELECT id FROM cl_demand WHERE owner_id=?)",user);
            jdbc.update("DELETE FROM cl_demand WHERE owner_id=?",user);
        }
        for(long user:users) jdbc.update("DELETE FROM cl_item WHERE owner_id=?",user);
        for(long user:users) {
            jdbc.update("DELETE FROM cl_notification WHERE user_id=?",user);
            jdbc.update("DELETE FROM cl_auth_session WHERE user_id=?",user);
            jdbc.update("DELETE FROM cl_user WHERE id=?",user);
        }
        assertEquals(baseline,businessRows(),"Cleanup must preserve all pre-existing business and session rows");
    }

    @Test void adminReadPermissionNeverGrantsParticipantAccessOrActions() throws Exception {
        var ring=ring(2);var before=businessRows();
        for(String path:List.of("/api/admin/exchanges","/api/admin/exchanges/"+ring.id())) {
            call("GET",path,null,null,401);call("GET",path,"invalid-test-token",null,401);
            call("GET",path,a.token(),null,403);call("GET",path,c.token(),null,403);
            call("GET",path,admin.token(),null,200);
        }
        var detail=call("GET","/api/admin/exchanges/"+ring.id(),admin.token(),null,200);
        assertEquals(Set.of("exchange","events","creation"),fields(detail));
        assertTrue(detail.at("/exchange/allowedActions").isEmpty());
        assertFalse(call("GET","/api/exchanges/"+ring.id(),a.token(),null,200).path("allowedActions").isEmpty());
        call("GET","/api/exchanges/"+ring.id(),admin.token(),null,404);
        for(var action:Map.<String,Object>of("confirm",Map.of("version",0),"cancel",Map.of("version",0,"reason","不应代办"),
            "handoff",Map.of("version",0,"kind","HANDED_OFF","acknowledged",true),"dispute",Map.of("version",0,"reason","不应代办")).entrySet())
            call("POST","/api/exchanges/"+ring.id()+"/"+action.getKey(),admin.token(),action.getValue(),404);
        assertEquals(before,businessRows());
    }

    @Test void allSixStatesAreFilterableAndPagesKeepStableTotalsAndEmptyOverflow() throws Exception {
        List<Long> ids=new ArrayList<>();
        for(String state:List.of("AWAITING_CONFIRMATION","READY","COMPLETED","CANCELLED","EXPIRED","DISPUTED")) {
            long id=ring(2).id();ids.add(id);
            if(Set.of("READY","COMPLETED","DISPUTED").contains(state)) ready(id);
            if("CANCELLED".equals(state)) lifecycle.cancel(a.id(),id,0,"虚构取消");
            if("EXPIRED".equals(state)) expire(id);
            if("COMPLETED".equals(state)) complete(id);
            if("DISPUTED".equals(state)) {lifecycle.handoff(a.id(),id,2,HandoffKind.HANDED_OFF,"已交付");lifecycle.dispute(b.id(),id,3,"虚构争议");}
            jdbc.update("UPDATE cl_exchange SET created_at='2026-09-08 01:00:00' WHERE id=?",id);
        }
        var before=businessRows();
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange",Long.class);
        var expected=jdbc.queryForList("SELECT id FROM cl_exchange ORDER BY created_at DESC,id DESC LIMIT 2",Long.class);
        var page=call("GET","/api/admin/exchanges?page=1&size=2",admin.token(),null,200);
        assertEquals(count,page.path("total").asLong());assertEquals(expected,json.convertValue(page.path("records").findValues("id"),new com.fasterxml.jackson.core.type.TypeReference<List<Long>>(){}));
        for(var record:page.path("records")) assertTrue(record.path("allowedActions").isEmpty());
        var overflow=call("GET","/api/admin/exchanges?page=999&size=2",admin.token(),null,200);
        assertEquals(count,overflow.path("total").asLong());assertTrue(overflow.path("records").isEmpty());
        for(String state:ExchangeQueryService.STATUSES) {
            var filtered=call("GET","/api/admin/exchanges?status="+state,admin.token(),null,200);
            assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange WHERE status=?",Long.class,state),filtered.path("total").asLong());
            for(var record:filtered.path("records")) assertEquals(state,record.path("status").asText());
        }
        assertEquals(before,businessRows());
    }

    @Test void rejectsMalformedDuplicateAndUndeclaredParametersAndUnavailableSessions() throws Exception {
        long id=ring(2).id();var before=businessRows();
        for(String query:List.of("page=0","size=0","size=101","page=1.0","page=-1","size=","page=2147483648",
            "status=","status=OTHER","page=1&page=2","status=READY&status=READY","userId=1","ownerId=1"))
            call("GET","/api/admin/exchanges?"+query,admin.token(),null,400);
        for(String suffix:List.of("/0","/-1","/bad","/9223372036854775808","/"+id+"?page=1"))
            call("GET","/api/admin/exchanges"+suffix,admin.token(),null,400);
        call("GET","/api/admin/exchanges/9223372036854775807",admin.token(),null,404);
        assertEquals(before,businessRows());
        jdbc.update("UPDATE cl_user SET status='DISABLED' WHERE id=?",admin.id());
        call("GET","/api/admin/exchanges",admin.token(),null,401);
    }

    @Test void safeCreationProjectionUsesOnlyOriginalAllowlistedFieldsAndPersistentDirections() throws Exception {
        var ring=ring(3);
        String saved=jdbc.queryForObject("SELECT creation_snapshot FROM cl_exchange WHERE id=?",String.class,ring.id());
        ObjectNode snapshot=(ObjectNode)json.readTree(saved);
        String originalTitle=snapshot.at("/recommendation/flows/0/itemTitle").asText();
        String secret="private-field-must-never-be-returned";
        snapshot.put("requestDigest",secret);snapshot.put("idempotencyKey",secret);
        ((ObjectNode)snapshot.at("/recommendation/flows/0")).put("description",secret).put("passwordHash",secret);
        jdbc.update("UPDATE cl_exchange SET creation_snapshot=?,expiry_failure_code='STATE_CONFLICT',expiry_retry_count=3 WHERE id=?",json.writeValueAsString(snapshot),ring.id());
        jdbc.update("UPDATE cl_item SET title=?,description=?,owner_id=? WHERE id=?",secret,secret,c.id(),ring.items().get(0));
        jdbc.update("UPDATE cl_demand SET description=? WHERE owner_id=?",secret,a.id());
        var before=businessRows();
        var detail=call("GET","/api/admin/exchanges/"+ring.id(),admin.token(),null,200);
        var created=detail.path("creation");assertEquals(Set.of("ruleVersion","flows"),fields(created));
        assertEquals(originalTitle,created.at("/flows/0/itemTitle").asText());
        for(var flow:created.path("flows")) assertEquals(Set.of("itemId","itemTitle","fromUserId","toUserId","demandId","matchedCategoryName","reason"),fields(flow));
        assertEquals(a.id(),detail.at("/exchange/flows/0/fromUserId").asLong());
        String response=detail.toString();assertFalse(response.contains(secret));
        for(String forbidden:List.of("requestDigest","idempotencyKey","creationSnapshot","demandSnapshot","passwordHash","expiryRetry","expiryFailure"))
            assertFalse(response.contains(forbidden));
        assertEquals(before,businessRows());
    }

    @Test void eventTimelineReadsPersistedOrderCurrentNamesAndNullSystemActor() throws Exception {
        long id=ring(2).id();lifecycle.confirm(a.id(),id,0);lifecycle.cancel(b.id(),id,1,"  虚构取消原因  ");
        jdbc.update("UPDATE cl_user SET display_name='更新后的公开名称' WHERE id=?",b.id());
        long expired=ring(2).id();expire(expired);var before=businessRows();
        var events=call("GET","/api/admin/exchanges/"+id,admin.token(),null,200).path("events");
        assertEquals(2,events.size());assertEquals("CONFIRMED",events.get(0).path("eventType").asText());
        assertEquals("CANCELLED",events.get(1).path("eventType").asText());
        assertEquals(1,events.get(0).path("newVersion").asInt());assertEquals(2,events.get(1).path("newVersion").asInt());
        assertEquals("更新后的公开名称",events.get(1).path("actorDisplayName").asText());
        assertEquals("虚构取消原因",events.get(1).path("reason").asText());
        for(var event:events) {
            assertEquals(Set.of("id","eventType","actorId","actorDisplayName","previousStatus","newStatus","previousVersion","newVersion","reason","occurredAt"),fields(event));
            assertDoesNotThrow(()->Instant.parse(event.path("occurredAt").asText()));
        }
        var event=call("GET","/api/admin/exchanges/"+expired,admin.token(),null,200).at("/events/0");
        assertEquals("EXPIRED",event.path("eventType").asText());assertTrue(event.path("actorId").isNull());assertTrue(event.path("actorDisplayName").isNull());
        assertEquals(before,businessRows());
    }

    @Test void legacyAndExpiredReadOnlyRecordsNeverInventSnapshotsEventsOrTransitions() throws Exception {
        long legacy=ring(2).id(),due=ring(2).id();
        jdbc.update("UPDATE cl_exchange SET request_digest=NULL,rule_version=NULL,creation_snapshot=NULL WHERE id=?",legacy);
        jdbc.update("UPDATE cl_exchange SET expires_at=? WHERE id=?",clock.now().minusSeconds(1),due);
        var before=businessRows();
        var old=call("GET","/api/admin/exchanges/"+legacy,admin.token(),null,200);
        assertTrue(old.path("creation").isNull());assertTrue(old.path("events").isEmpty());assertTrue(old.at("/exchange/allowedActions").isEmpty());
        var expired=call("GET","/api/admin/exchanges/"+due,admin.token(),null,200);
        assertEquals("AWAITING_CONFIRMATION",expired.at("/exchange/status").asText());assertTrue(expired.path("events").isEmpty());
        call("GET","/api/admin/exchanges",admin.token(),null,200);assertEquals(before,businessRows());
    }

    @Test void corruptSnapshotsAndIncompleteRingsFailClosedWithoutWriting() throws Exception {
        long id=ring(2).id();String saved=jdbc.queryForObject("SELECT creation_snapshot FROM cl_exchange WHERE id=?",String.class,id);
        ObjectNode wrong=(ObjectNode)json.readTree(saved);((ObjectNode)wrong.at("/recommendation/flows/0")).put("fromUserId",admin.id());
        for(String bad:List.of("{}","not-json-private-payload",json.writeValueAsString(wrong))) {
            jdbc.update("UPDATE cl_exchange SET creation_snapshot=? WHERE id=?",bad,id);var before=businessRows();
            call("GET","/api/admin/exchanges/"+id,admin.token(),null,409);assertEquals(before,businessRows());
        }
        jdbc.update("UPDATE cl_exchange SET creation_snapshot=? WHERE id=?",saved,id);
        jdbc.update("DELETE FROM cl_exchange_participant WHERE exchange_id=? AND user_id=?",id,b.id());
        var before=businessRows();call("GET","/api/admin/exchanges/"+id,admin.token(),null,409);
        call("GET","/api/admin/exchanges",admin.token(),null,409);assertEquals(before,businessRows());
    }

    @Test void mysqlAdminDetailKeepsOneSnapshotWhileAConcurrentCancellationCommits() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("campus.mysql-test"),"Snapshot and non-locking evidence requires isolated MySQL");
        long id=ring(2).id();var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var read=pool.submit(()->{
                ReadProbe.before.set(statement->{if(statement.endsWith("ExchangeMapper.events")){reached.countDown();await(release);}});
                try{return call("GET","/api/admin/exchanges/"+id,admin.token(),null,200);}finally{ReadProbe.before.remove();}
            });
            assertTrue(reached.await(10,TimeUnit.SECONDS));lifecycle.cancel(a.id(),id,0,"并发取消");
            release.countDown();var prior=read.get(10,TimeUnit.SECONDS);
            assertEquals("AWAITING_CONFIRMATION",prior.at("/exchange/status").asText());assertTrue(prior.path("events").isEmpty());
            var current=call("GET","/api/admin/exchanges/"+id,admin.token(),null,200);
            assertEquals("CANCELLED",current.at("/exchange/status").asText());assertEquals(1,current.path("events").size());
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }


    @Test void disputedExchangeCanResumeWithoutForgingOrLosingHandoff() throws Exception {
        long id=ring(2).id();ready(id);lifecycle.handoff(a.id(),id,2,HandoffKind.HANDED_OFF,"原交接");lifecycle.dispute(b.id(),id,3,"核对物品");
        var handed=jdbc.queryForMap("SELECT * FROM cl_exchange_participant WHERE exchange_id=? AND user_id=?",id,a.id());
        var body=Map.of("version",4,"decision","RESUME","reason","双方已核对","returnConfirmed",false);
        call("POST","/api/admin/exchange-disputes/"+id+"/resolve",a.token(),body,403);
        var result=call("POST","/api/admin/exchange-disputes/"+id+"/resolve",admin.token(),body,200);
        assertEquals("READY",result.at("/exchange/status").asText());assertEquals(5,result.at("/exchange/version").asInt());
        assertEquals(handed,jdbc.queryForMap("SELECT * FROM cl_exchange_participant WHERE exchange_id=? AND user_id=?",id,a.id()));
        call("POST","/api/admin/exchange-disputes/"+id+"/resolve",admin.token(),body,200);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange_resolution WHERE exchange_id=?",Integer.class,id));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM cl_notification WHERE source_key=?",Integer.class,"EXCHANGE:"+id+":5"));
        call("GET","/api/exchanges/"+id+"/resolutions",c.token(),null,404);
        assertEquals(1,call("GET","/api/exchanges/"+id+"/resolutions",a.token(),null,200).size());
        lifecycle.handoff(b.id(),id,5,HandoffKind.RECEIVED,"");lifecycle.handoff(b.id(),id,6,HandoffKind.HANDED_OFF,"");lifecycle.handoff(a.id(),id,7,HandoffKind.RECEIVED,"");
        assertEquals("COMPLETED",call("GET","/api/exchanges/"+id,a.token(),null,200).path("status").asText());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_history WHERE exchange_id=?",Integer.class,id));
    }
    @Test void cancellationRequiresReturnedItemsAndKeepsResolutionAuditOnReplay() throws Exception {
        var ring=ring(2);long id=ring.id();ready(id);lifecycle.handoff(a.id(),id,2,HandoffKind.HANDED_OFF,"");lifecycle.dispute(b.id(),id,3,"待归还");
        var body=Map.of("version",4,"decision","CANCEL","reason","物品已全部归还","returnConfirmed",true);
        call("POST","/api/admin/exchange-disputes/"+id+"/resolve",admin.token(),Map.of("version",4,"decision","CANCEL","reason","未归还","returnConfirmed",false),400);
        call("POST","/api/admin/exchange-disputes/"+id+"/resolve",admin.token(),body,200);
        call("POST","/api/admin/exchange-disputes/"+id+"/resolve",admin.token(),body,200);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold WHERE exchange_id=?",Integer.class,id));
        assertEquals("CANCELLED",call("GET","/api/exchanges/"+id,a.token(),null,200).path("status").asText());
        for(long item:ring.items())assertEquals("AVAILABLE",jdbc.queryForObject("SELECT status FROM cl_item WHERE id=?",String.class,item));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_history WHERE exchange_id=?",Integer.class,id));
    }
    @Test void simultaneousResolutionDecisionsHaveOnlyOneWinner() throws Exception {
        long id=ring(2).id();ready(id);lifecycle.handoff(a.id(),id,2,HandoffKind.HANDED_OFF,"");lifecycle.dispute(a.id(),id,3,"核对");
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<Integer>>();
            for(String decision:List.of("RESUME","CANCEL"))futures.add(pool.submit(()->{start.await();try{lifecycle.resolve(admin.id(),id,new edu.campusloop.web.exchange.dto.ResolveDisputeRequest(4,decision,"已协商",decision.equals("CANCEL")));return 200;}catch(edu.campusloop.common.ApiException e){return e.getStatus();}}));
            start.countDown();var outcomes=new ArrayList<Integer>();for(var f:futures)outcomes.add(f.get(15,TimeUnit.SECONDS));Collections.sort(outcomes);assertEquals(List.of(200,409),outcomes);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange_resolution WHERE exchange_id=?",Integer.class,id));
        }finally{pool.shutdownNow();}
    }
    private Ring ring(int length) {
        var people=List.of(a,b,c).subList(0,length);List<Long> items=new ArrayList<>(),demands=new ArrayList<>();
        for(int i=0;i<length;i++) items.add(item(people.get(i).id(),i+1));
        for(int i=0;i<length;i++) demands.add(demand(people.get(i).id(),(i+length-1)%length+1,items.get(i)));
        List<ExchangeCreationCommand.ExpectedFlow> flows=new ArrayList<>();
        for(int i=0;i<length;i++) flows.add(new ExchangeCreationCommand.ExpectedFlow(items.get(i),0,demands.get((i+1)%length),0));
        return new Ring(creation.create(a.id(),new ExchangeCreationCommand("independent-v2",UUID.randomUUID().toString(),flows)),items);
    }
    private void ready(long id) {lifecycle.confirm(a.id(),id,0);lifecycle.confirm(b.id(),id,1);}
    private void complete(long id) {
        lifecycle.handoff(a.id(),id,2,HandoffKind.HANDED_OFF,"");lifecycle.handoff(b.id(),id,3,HandoffKind.RECEIVED,"");
        lifecycle.handoff(b.id(),id,4,HandoffKind.HANDED_OFF,"");lifecycle.handoff(a.id(),id,5,HandoffKind.RECEIVED,"");
    }
    private void expire(long id) {jdbc.update("UPDATE cl_exchange SET expires_at=? WHERE id=?",clock.now().minusSeconds(1),id);assertTrue(lifecycle.expire(id));}
    private Account account(boolean administrator) throws Exception {
        String name="admin_exchange_"+UUID.randomUUID(),password=UUID.randomUUID().toString();
        long id=call("POST","/api/auth/register",null,Map.of("username",name,"password",password,"displayName","虚构交换同学"),200).path("id").asLong();users.add(id);
        if(administrator) jdbc.update("UPDATE cl_user SET role='ADMIN',admin_permissions='ALL' WHERE id=?",id);
        return new Account(id,call("POST","/api/auth/login",null,Map.of("username",name,"password",password),200).path("token").asText());
    }
    private long item(long user,int category) {
        String title="创建时公开物品_"+UUID.randomUUID();
        jdbc.update("INSERT INTO cl_item(owner_id,title,description,category_id,condition_level,tags_json,wanted_category_id,wanted_tags_json,status,review_basis) VALUES(?,?,'私人当前物品说明',?,1,'[]',6,'[]','AVAILABLE','LEGACY_DIRECT')",user,title,category);
        return jdbc.queryForObject("SELECT id FROM cl_item WHERE title=?",Long.class,title);
    }
    private long demand(long user,int category,long item) {
        jdbc.update("INSERT INTO cl_demand(owner_id,category_id,description,preferred_tags_json,status,version) VALUES(?,?,'私人需求说明不可返回','[]','ACTIVE',0)",user,category);
        long id=jdbc.queryForObject("SELECT MAX(id) FROM cl_demand WHERE owner_id=?",Long.class,user);
        jdbc.update("INSERT INTO cl_demand_item(demand_id,item_id) VALUES(?,?)",id,item);return id;
    }
    private Map<String,List<Map<String,Object>>> businessRows() {
        Map<String,List<Map<String,Object>>> result=new LinkedHashMap<>();
        for(String table:List.of("cl_user","cl_auth_session","cl_item","cl_demand","cl_exchange","cl_exchange_participant","cl_exchange_event","cl_item_history"))
            result.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY id"));
        result.put("cl_exchange_demand",jdbc.queryForList("SELECT * FROM cl_exchange_demand ORDER BY exchange_id,demand_id"));
        result.put("cl_demand_item",jdbc.queryForList("SELECT * FROM cl_demand_item ORDER BY demand_id,item_id"));
        result.put("cl_item_hold",jdbc.queryForList("SELECT * FROM cl_item_hold ORDER BY item_id"));return result;
    }
    private JsonNode call(String method,String path,String token,Object body,int expected) throws Exception {
        var req=request(HttpMethod.valueOf(method),path);if(token!=null) req.header("Authorization","Bearer "+token);
        if(body!=null) req.contentType("application/json").content(json.writeValueAsString(body));
        var response=mvc.perform(req).andReturn().getResponse();assertEquals(expected,response.getStatus(),method+" "+path);
        var value=json.readTree(response.getContentAsString());assertEquals(expected,value.path("code").asInt());return value.path("data");
    }
    private static Set<String> fields(JsonNode value) {Set<String> names=new HashSet<>();value.fieldNames().forEachRemaining(names::add);return names;}
    private static void await(CountDownLatch latch) {try{assertTrue(latch.await(10,TimeUnit.SECONDS));}catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}}
    @TestConfiguration static class ProbeConfiguration {@Bean ReadProbe adminExchangeReadProbe(){return new ReadProbe();}}
    @Intercepts(@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    static class ReadProbe implements Interceptor {
        static final ThreadLocal<java.util.function.Consumer<String>> before=new ThreadLocal<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var hook=before.get();if(hook!=null) hook.accept(((MappedStatement)invocation.getArgs()[0]).getId());return invocation.proceed();
        }
    }
}
