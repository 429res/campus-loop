package edu.campusloop;

import com.fasterxml.jackson.databind.*;
import edu.campusloop.common.ApiException;
import edu.campusloop.exchange.*;
import edu.campusloop.matching.*;
import edu.campusloop.web.exchange.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** V2 rows below are explicit read fixtures, never a substitute transaction implementation. */
@SpringBootTest
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles(resolver=CampusIntegrationTest.Profile.class)
class ExchangeDomainIntegrationTest {
    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry registry) {
        boolean mysql=Boolean.getBoolean("campus.mysql-test");
        String url=mysql?System.getenv("TEST_DB_URL"):"jdbc:h2:mem:campus_loop_exchange_domain_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        String username=mysql?System.getenv("TEST_DB_USERNAME"):"sa", password=mysql?System.getenv("TEST_DB_PASSWORD"):"";
        if(mysql && (url==null || !url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/campus_loop_[a-z0-9_]*test(?:\\?.*)?")))
            throw new IllegalStateException("Exchange tests require an isolated localhost campus_loop_*test schema");
        if(username==null || password==null) throw new IllegalStateException("Explicit test credentials required");
        registry.add("spring.datasource.url",()->url);registry.add("spring.datasource.username",()->username);registry.add("spring.datasource.password",()->password);
        registry.add("spring.datasource.driver-class-name",()->mysql?"com.mysql.cj.jdbc.Driver":"org.h2.Driver");
        registry.add("spring.flyway.url",()->url);registry.add("spring.flyway.user",()->username);registry.add("spring.flyway.password",()->password);
        registry.add("campus.bootstrap-enabled",()->false);registry.add("campus.registration-mode",()->"DEVELOPMENT_SELF_SERVICE");
        registry.add("campus.jwt-secret",()->UUID.randomUUID().toString()+UUID.randomUUID());
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExchangeCandidateReader candidates;
    @Autowired ApplicationContext context;
    private final List<Long> users=new ArrayList<>();
    private final List<Long> exchanges=new ArrayList<>();
    private Account a,b,c,outsider,admin;
    private String prefix;
    private record Account(long id,String token) {}
    private Map<String,List<Map<String,Object>>> baseline;
    @BeforeEach void fixtures() throws Exception {
        baseline=businessRows();prefix="b03_"+UUID.randomUUID().toString().substring(0,8);
        a=account(false);b=account(false);c=account(false);outsider=account(false);admin=account(true);
    }
    @AfterEach void removeOnlyOwnFixtures() {
        for(long exchange:exchanges) {
            jdbc.update("DELETE FROM cl_item_hold WHERE exchange_id=?",exchange);
            jdbc.update("DELETE FROM cl_exchange_participant WHERE exchange_id=?",exchange);
            jdbc.update("DELETE FROM cl_exchange WHERE id=?",exchange);
        }
        for(long user:users) {
            jdbc.update("DELETE FROM cl_demand_item WHERE demand_id IN (SELECT id FROM cl_demand WHERE owner_id=?)",user);
            jdbc.update("DELETE FROM cl_demand WHERE owner_id=?",user);
            jdbc.update("DELETE FROM cl_item WHERE owner_id=?",user);
            jdbc.update("DELETE FROM cl_auth_session WHERE user_id=?",user);
            jdbc.update("DELETE FROM cl_user WHERE id=?",user);
        }
        assertEquals(baseline,businessRows(),"Fixture cleanup must preserve all pre-existing business rows");
    }
    @Test void storedTwoAndThreePartyRingsAreReadableByEachParticipantWithPersistedDirections() throws Exception {
        long x=item(a,1), y=item(b,2), z=item(c,3);
        long pair=seedStoredExchange(List.of(a,b),List.of(x,y),"AWAITING_CONFIRMATION");
        long triple=seedStoredExchange(List.of(c,a,b),List.of(z,x,y),"READY");
        jdbc.update("UPDATE cl_exchange_participant SET confirmed_at='2026-09-08 01:00:00' WHERE exchange_id=? AND user_id=?",pair,b.id());
        var before=businessRows();
        for(Account viewer:List.of(a,b)) {
            var detail=call("GET","/api/exchanges/"+pair,viewer.token(),null,200);
            assertEquals(pair,detail.path("id").asLong());assertEquals("AWAITING_CONFIRMATION",detail.path("status").asText());
            assertEquals("PENDING",detail.at("/participants/0/confirmationStatus").asText());
            assertEquals("CONFIRMED",detail.at("/participants/1/confirmationStatus").asText());
            assertEquals(y,detail.at("/participants/0/receivedItemId").asLong());
            assertEquals(b.id(),detail.at("/flows/0/toUserId").asLong());
            assertEquals("2026-09-09T01:00:00Z",detail.path("expiresAt").asText());
            assertTrue(detail.path("allowedActions").isEmpty());
        }
        for(Account viewer:List.of(a,b,c)) {
            var detail=call("GET","/api/exchanges/"+triple,viewer.token(),null,200);
            assertEquals(3,detail.path("participants").size());
            assertEquals(List.of(a.id(),b.id(),c.id()),json.convertValue(detail.path("flows").findValues("fromUserId"),new com.fasterxml.jackson.core.type.TypeReference<List<Long>>(){}));
            assertEquals(z,detail.at("/participants/0/receivedItemId").asLong());
        }
        assertEquals(before,businessRows());
    }
    @Test void membershipIsServerScopedAndAdminHasNoPrivateRouteBypass() throws Exception {
        long id=seedStoredExchange(List.of(a,b),List.of(item(a,1),item(b,2)),"AWAITING_CONFIRMATION");
        var before=businessRows();
        for(Account viewer:List.of(outsider,admin)) {
            call("GET","/api/exchanges/"+id,viewer.token(),null,404);
            assertEquals(0,call("GET","/api/exchanges/mine",viewer.token(),null,200).path("total").asInt());
        }
        call("GET","/api/exchanges/999999999",outsider.token(),null,404);
        call("GET","/api/exchanges/"+id,null,null,401);call("GET","/api/exchanges/mine",null,null,401);
        for(String suffix:List.of("?userId="+a.id(),"?ownerId="+a.id(),"?page=1&page=2","?status=READY&status=COMPLETED"))
            call("GET","/api/exchanges/mine"+suffix,outsider.token(),null,400);
        call("GET","/api/exchanges/"+id+"?ownerId="+a.id(),outsider.token(),null,400);
        call("GET","/api/admin/exchanges",a.token(),null,404);
        call("GET","/api/admin/exchanges",admin.token(),null,404);
        assertEquals(before,businessRows());
    }
    @Test void paginationFiltersAndStableIdOrderCountOnlyMembership() throws Exception {
        List<Long> own=new ArrayList<>();
        for(String state:List.of("AWAITING_CONFIRMATION","READY","COMPLETED","CANCELLED","EXPIRED","DISPUTED"))
            own.add(seedStoredExchange(List.of(a,b),List.of(item(a,1),item(b,2)),state));
        seedStoredExchange(List.of(c,outsider),List.of(item(c,1),item(outsider,2)),"READY");
        var first=call("GET","/api/exchanges/mine?size=2",a.token(),null,200);
        assertEquals(6,first.path("total").asInt());assertEquals(own.get(5).longValue(),first.at("/records/0/id").asLong());
        assertEquals(own.get(3).longValue(),call("GET","/api/exchanges/mine?size=2&page=2",a.token(),null,200).at("/records/0/id").asLong());
        var beyond=call("GET","/api/exchanges/mine?size=2&page=10",a.token(),null,200);
        assertTrue(beyond.path("records").isEmpty());assertEquals(6,beyond.path("total").asInt());
        for(String state:ExchangeQueryService.STATUSES) assertEquals(1,call("GET","/api/exchanges/mine?status="+state,a.token(),null,200).path("total").asInt());
        for(String suffix:List.of("?page=0","?size=0","?size=101","?page=2147483648","?page=1.5","?status=UNKNOWN","?status="))
            call("GET","/api/exchanges/mine"+suffix,a.token(),null,400);
        call("GET","/api/exchanges/0",a.token(),null,400);
    }
    @Test void unavailableCreateNeverFabricatesSuccessOrPartialWritesAndRejectsForgedFields() throws Exception {
        assertTrue(context.getBeansOfType(ExchangeCreationTransaction.class).isEmpty());
        var body=body(1,2,11,12);var before=businessRows();
        call("POST","/api/exchanges",null,body,401);
        call("POST","/api/exchanges",a.token(),body,501);
        call("POST","/api/exchanges",a.token(),body,501); // unavailable is not idempotent creation evidence
        call("POST","/api/exchanges?ownerId="+a.id(),a.token(),body,400);
        for(String field:List.of("ownerId","participants","initiatorId","score","reason","status","confirmedAt","expiresAt")) {
            var forged=new LinkedHashMap<>(body);forged.put(field,"forged");call("POST","/api/exchanges",a.token(),forged,400);
        }
        var old=new LinkedHashMap<>(body);old.put("ruleVersion","legacy-v1");call("POST","/api/exchanges",a.token(),old,409);
        for(String action:List.of("confirm","cancel","handoff")) call("POST","/api/exchanges/1/"+action,admin.token(),Map.of("version",0),501);
        assertEquals(before,businessRows());
    }
    @Test void databaseOwnerDemandAndVersionSnapshotsValidateBothRingLengthsWithoutWriting() {
        long x=item(a,1),y=item(b,2),z=item(c,3);
        long da=demand(a,2,x),db=demand(b,1,y);
        jdbc.update("UPDATE cl_item SET version=3 WHERE id=?",x);
        jdbc.update("UPDATE cl_demand SET version=4 WHERE id=?",db);
        var cmd=new ExchangeCreationCommand("independent-v2","database-key",List.of(flow(x,3,db,4),flow(y,0,da,0)));
        var before=businessRows();
        var validator=new ExchangeCycleValidator();
        assertEquals(2,validator.validate(a.id(),cmd,candidates.snapshot(List.of(x,y))).recommendation().length());
        rejected(403,()->validator.validate(outsider.id(),cmd,candidates.snapshot(List.of(x,y))));
        rejected(409,()->validator.validate(a.id(),new ExchangeCreationCommand("independent-v2","database-key",List.of(flow(x,2,db,4),flow(y,0,da,0))),candidates.snapshot(List.of(x,y))));
        assertEquals(before,businessRows());
        jdbc.update("UPDATE cl_demand SET category_id=3,version=1 WHERE id=?",da);
        long dc=demand(c,2,z);
        var three=new ExchangeCreationCommand("independent-v2","database-key",List.of(flow(x,3,db,4),flow(y,0,dc,0),flow(z,0,da,1)));
        before=businessRows();
        assertEquals(3,validator.validate(c.id(),three,candidates.snapshot(List.of(x,y,z))).recommendation().length());
        assertEquals(before,businessRows());
    }
    @Test void changedStateOwnershipDemandAndEvenExpiredHoldsRejectDomainValidation() {
        long x=item(a,1),y=item(b,2),da=demand(a,2,x),db=demand(b,1,y);
        var cmd=new ExchangeCreationCommand("independent-v2","database-key",List.of(flow(x,0,db,0),flow(y,0,da,0)));
        for(String state:List.of("DRAFT","PENDING_REVIEW","REJECTED","HIDDEN","RESERVED","EXCHANGED")) {
            jdbc.update("UPDATE cl_item SET status=? WHERE id=?",state,x);assertStaleWithoutWrites(cmd,x,y);
        }
        jdbc.update("UPDATE cl_item SET status='AVAILABLE' WHERE id=?",x);
        for(String state:List.of("INACTIVE","DELETED")) { jdbc.update("UPDATE cl_demand SET status=? WHERE id=?",state,db);assertStaleWithoutWrites(cmd,x,y); }
        jdbc.update("UPDATE cl_demand SET status='ACTIVE' WHERE id=?",db);
        jdbc.update("UPDATE cl_user SET status='DISABLED' WHERE id=?",b.id());assertStaleWithoutWrites(cmd,x,y);
        jdbc.update("UPDATE cl_user SET status='ACTIVE' WHERE id=?",b.id());
        jdbc.update("UPDATE cl_item SET owner_id=? WHERE id=?",c.id(),y);assertStaleWithoutWrites(cmd,x,y);
        jdbc.update("UPDATE cl_item SET owner_id=? WHERE id=?",b.id(),y);
        jdbc.update("DELETE FROM cl_demand_item WHERE demand_id=?",db);assertStaleWithoutWrites(cmd,x,y);
        jdbc.update("INSERT INTO cl_demand_item(demand_id,item_id) VALUES(?,?)",db,y);
        long stored=seedStoredExchange(List.of(a,b),List.of(x,y),"CANCELLED");
        jdbc.update("INSERT INTO cl_item_hold(item_id,exchange_id,expires_at) VALUES(?,?,'2020-01-01 00:00:00')",x,stored);
        assertStaleWithoutWrites(cmd,x,y);
        jdbc.update("DELETE FROM cl_item_hold WHERE exchange_id=?",stored);
        jdbc.update("UPDATE cl_exchange SET status='AWAITING_CONFIRMATION' WHERE id=?",stored);
        assertStaleWithoutWrites(cmd,x,y); // missing hold does not bypass an active participant reference
    }
    @Test void recommendationPreconditionMetadataIsFromDatabaseAndReadOnly() throws Exception {
        long x=item(a,1),y=item(b,2);long da=demand(a,2,x),db=demand(b,1,y);
        jdbc.update("UPDATE cl_item SET version=9 WHERE id=?",x);jdbc.update("UPDATE cl_demand SET version=7 WHERE id=?",db);
        var before=businessRows();
        var response=call("GET","/api/matches/independent",a.token(),null,200);
        var rec=response.at("/recommendations/0");assertEquals("independent-v2",rec.path("ruleVersion").asText());
        assertEquals(9,rec.at("/participants/0/itemVersion").asInt());assertEquals(7,rec.at("/flows/0/demandVersion").asInt());
        assertEquals(db,rec.at("/flows/0/demandId").asLong());assertEquals(60,rec.path("score").asInt());
        assertEquals(before,businessRows());
    }
    @Test void historicalReadDoesNotExposeCurrentPrivateItemOrFabricateTimeoutActions() throws Exception {
        long x=item(a,1),y=item(b,2),id=seedStoredExchange(List.of(a,b),List.of(x,y),"AWAITING_CONFIRMATION");
        jdbc.update("UPDATE cl_item SET title='later private title',description='later private description',status='HIDDEN',owner_id=? WHERE id=?",c.id(),x);
        jdbc.update("UPDATE cl_exchange SET expires_at='2020-01-01 00:00:00' WHERE id=?",id);
        var before=businessRows();var detail=call("GET","/api/exchanges/"+id,a.token(),null,200);
        assertEquals(a.id(),detail.at("/flows/0/fromUserId").asLong());
        assertEquals("AWAITING_CONFIRMATION",detail.path("status").asText());assertTrue(detail.path("allowedActions").isEmpty());
        for(String secret:List.of("later private", "idempotencyKey", "password", "token", "description", "reviewReason")) assertFalse(detail.toString().contains(secret));
        call("GET","/api/exchanges/"+id,c.token(),null,404);
        assertEquals(before,businessRows());
    }
    @Test void incompletePersistedRingFailsClosedAndSnapshotAdapterRejectsUnboundedInputs() throws Exception {
        long id=seedStoredExchange(List.of(a,b),List.of(item(a,1),item(b,2)),"AWAITING_CONFIRMATION");
        jdbc.update("UPDATE cl_exchange_participant SET recipient_user_id=user_id WHERE exchange_id=? AND user_id=?",id,a.id());
        var before=businessRows();call("GET","/api/exchanges/"+id,a.token(),null,409);assertEquals(before,businessRows());
        for(List<Long> ids:List.of(List.of(1L),List.of(1L,1L),List.of(1L,2L,3L,4L))) rejected(400,()->candidates.snapshot(ids));
    }
    private void assertStaleWithoutWrites(ExchangeCreationCommand cmd,long x,long y) {
        var before=businessRows();rejected(409,()->new ExchangeCycleValidator().validate(a.id(),cmd,candidates.snapshot(List.of(x,y))));assertEquals(before,businessRows());
    }
    private void rejected(int code,Runnable operation) { assertEquals(code,assertThrows(ApiException.class,operation::run).getStatus()); }
    private ExchangeCreationCommand.ExpectedFlow flow(long item,int iv,long demand,int dv) { return new ExchangeCreationCommand.ExpectedFlow(item,iv,demand,dv); }
    private Map<String,Object> body(long x,long y,long da,long db) {
        return Map.of("ruleVersion","independent-v2","idempotencyKey","fictional-key","flows",List.of(
            Map.of("itemId",x,"itemVersion",0,"demandId",db,"demandVersion",0),Map.of("itemId",y,"itemVersion",0,"demandId",da,"demandVersion",0)));
    }
    private Account account(boolean administrator) throws Exception {
        String name=prefix+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
        long id=call("POST","/api/auth/register",null,Map.of("username",name,"password",password,"displayName","虚构交换同学"),200).path("id").asLong();users.add(id);
        if(administrator) jdbc.update("UPDATE cl_user SET role='ADMIN' WHERE id=?",id);
        return new Account(id,call("POST","/api/auth/login",null,Map.of("username",name,"password",password),200).path("token").asText());
    }
    private long item(Account owner,int category) {
        String title=prefix+UUID.randomUUID().toString().substring(0,8);
        jdbc.update("INSERT INTO cl_item(owner_id,title,description,category_id,condition_level,tags_json,wanted_category_id,wanted_tags_json,status,review_basis) VALUES(?,?,?, ?,1,'[]',6,'[]','AVAILABLE','LEGACY_DIRECT')",owner.id(),title,"私有测试说明",category);
        return jdbc.queryForObject("SELECT id FROM cl_item WHERE title=?",Long.class,title);
    }
    private long demand(Account owner,int category,long item) {
        jdbc.update("INSERT INTO cl_demand(owner_id,category_id,description,preferred_tags_json,status,version) VALUES(?,?,'私有需求','[]','ACTIVE',0)",owner.id(),category);
        long id=jdbc.queryForObject("SELECT MAX(id) FROM cl_demand WHERE owner_id=?",Long.class,owner.id());
        jdbc.update("INSERT INTO cl_demand_item(demand_id,item_id) VALUES(?,?)",id,item);return id;
    }
    private long seedStoredExchange(List<Account> people,List<Long> items,String state) {
        String key=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO cl_exchange(initiator_id,status,idempotency_key,expires_at,created_at) VALUES(?,?,?,'2026-09-09 01:00:00','2026-09-08 01:00:00')",people.get(0).id(),state,key);
        long id=jdbc.queryForObject("SELECT id FROM cl_exchange WHERE initiator_id=? AND idempotency_key=?",Long.class,people.get(0).id(),key);exchanges.add(id);
        for(int i=0;i<people.size();i++) jdbc.update("INSERT INTO cl_exchange_participant(exchange_id,user_id,offered_item_id,recipient_user_id) VALUES(?,?,?,?)",id,people.get(i).id(),items.get(i),people.get((i+1)%people.size()).id());
        return id;
    }
    private Map<String,List<Map<String,Object>>> businessRows() {
        Map<String,List<Map<String,Object>>> values=new LinkedHashMap<>();
        for(String table:List.of("cl_item","cl_demand","cl_exchange","cl_exchange_participant")) values.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY id"));
        values.put("cl_demand_item",jdbc.queryForList("SELECT * FROM cl_demand_item ORDER BY demand_id,item_id"));
        values.put("cl_item_hold",jdbc.queryForList("SELECT * FROM cl_item_hold ORDER BY item_id"));return values;
    }
    private JsonNode call(String method,String path,String token,Object body,int expected) throws Exception {
        var req=request(HttpMethod.valueOf(method),path);if(token!=null) req.header("Authorization","Bearer "+token);
        if(body!=null) req.contentType("application/json").content(json.writeValueAsString(body));
        var response=mvc.perform(req).andReturn().getResponse();assertEquals(expected,response.getStatus(),method+" "+path);
        JsonNode value=json.readTree(response.getContentAsString());assertEquals(expected,value.path("code").asInt());return value.path("data");
    }
}
