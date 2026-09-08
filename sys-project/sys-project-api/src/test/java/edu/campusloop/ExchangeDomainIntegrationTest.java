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
@org.springframework.context.annotation.Import(ExchangeDomainIntegrationTest.ProbeConfiguration.class)
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
        for(long user:users) exchanges.addAll(jdbc.queryForList("SELECT id FROM cl_exchange WHERE initiator_id=?",Long.class,user));
        for(long exchange:new LinkedHashSet<>(exchanges)) {
            jdbc.update("DELETE FROM cl_item_hold WHERE exchange_id=?",exchange);
            jdbc.update("DELETE FROM cl_exchange_demand WHERE exchange_id=?",exchange);
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
    @Test void creationPortIsInstalledButStaleOrForgedRequestsNeverWrite() throws Exception {
        assertEquals(1,context.getBeansOfType(ExchangeCreationTransaction.class).size());
        var body=body(1,2,11,12);var before=businessRows();
        call("POST","/api/exchanges",null,body,401);
        call("POST","/api/exchanges",a.token(),body,409);
        call("POST","/api/exchanges",a.token(),body,409); // stale requests never create an idempotency record
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
    @Autowired ExchangeCreationTransaction creation;
    @Autowired edu.campusloop.web.demand.service.DemandService demandWrites;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class ProbeConfiguration {
        @org.springframework.context.annotation.Bean SqlProbe sqlProbe() { return new SqlProbe(); }
    }
    @org.apache.ibatis.plugin.Intercepts({
        @org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="update",args={org.apache.ibatis.mapping.MappedStatement.class,Object.class}),
        @org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="query",args={org.apache.ibatis.mapping.MappedStatement.class,Object.class,org.apache.ibatis.session.RowBounds.class,org.apache.ibatis.session.ResultHandler.class})
    })
    static class SqlProbe implements org.apache.ibatis.plugin.Interceptor {
        static final ThreadLocal<java.util.function.Consumer<String>> before=new ThreadLocal<>(),after=new ThreadLocal<>();
        @Override public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            String id=((org.apache.ibatis.mapping.MappedStatement)invocation.getArgs()[0]).getId();
            if(before.get()!=null) before.get().accept(id);
            Object result=invocation.proceed();
            try { if(after.get()!=null) after.get().accept(id); }
            catch(org.springframework.dao.DataAccessException failure) {
                // Expose the real JDBC constraint failure to MyBatis's normal SQL exception translator.
                if(failure.getMostSpecificCause() instanceof java.sql.SQLException sql) throw sql;
                throw failure;
            }
            return result;
        }
    }

    @Test void realTwoAndThreePartyCreationUsesTheBEntryAndPersistentReadback() throws Exception {
        for(int length:List.of(2,3)) {
            var command=ring(length,"created-ring-"+length);
            var before=businessRows();
            call("GET","/api/matches/independent",a.token(),null,200);
            call("GET","/api/matches",null,null,200);
            assertEquals(before,businessRows(),"Recommendation GET must stay read-only");
            JsonNode result=call("POST","/api/exchanges",a.token(),command,200);
            long id=result.path("id").asLong();
            assertEquals(length,result.path("participants").size());
            assertEquals("AWAITING_CONFIRMATION",result.path("status").asText());
            assertEquals(0,result.path("version").asInt());
            assertEquals(86400,java.time.Duration.between(java.time.Instant.parse(result.path("createdAt").asText()),
                java.time.Instant.parse(result.path("expiresAt").asText())).getSeconds());
            for(var p:result.path("participants")) assertEquals("PENDING",p.path("confirmationStatus").asText());
            assertEquals(length,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold WHERE exchange_id=?",Integer.class,id));
            assertEquals(length,jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange_demand WHERE exchange_id=?",Integer.class,id));
            assertEquals(length,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item i JOIN cl_item_hold h ON h.item_id=i.id WHERE h.exchange_id=? AND i.status='RESERVED' AND i.version=1 AND i.review_basis='LEGACY_DIRECT'",Integer.class,id));
            var persisted=businessRows();
            var rotated=new ArrayList<>(command.flows());Collections.rotate(rotated,1);
            var retry=new ExchangeCreationCommand(command.ruleVersion(),command.idempotencyKey(),rotated);
            assertEquals(result,call("POST","/api/exchanges",a.token(),retry,200));
            assertEquals(result,call("GET","/api/exchanges/"+id,b.token(),null,200));
            call("GET","/api/exchanges/"+id,outsider.token(),null,404);
            assertEquals(persisted,businessRows(),"Retry/readback must not extend deadline or duplicate rows");
        }
    }

    @Test void sameKeyDifferentPayloadConflictsAndKeysAreScopedToCreator() throws Exception {
        var first=ring(2,"same-request-key");
        long id=creation.create(a.id(),first);
        var second=ring(2,"same-request-key");
        var before=businessRows();
        rejected(409,()->creation.create(a.id(),second));
        var changed=new ArrayList<>(first.flows());var f=changed.get(0);
        changed.set(0,flow(f.itemId(),1,f.demandId(),f.demandVersion()));
        rejected(409,()->creation.create(a.id(),new ExchangeCreationCommand(first.ruleVersion(),first.idempotencyKey(),changed)));
        assertEquals(before,businessRows());
        assertNotEquals(id,creation.create(b.id(),second)); // Same key, different legitimate creator and unoccupied ring.
        assertEquals(id,creation.create(a.id(),first));
    }

    @Test void selectedDemandFreezesAllMutationEndpointsAndHistoricalSnapshotSurvivesLaterEdits() throws Exception {
        var cmd=ring(2,"freeze-demand-key");long demand=cmd.flows().get(0).demandId();
        long offer=cmd.flows().get(1).itemId();
        long other=demand(b,1,offer); // Tie loses to the original lower demand ID.
        long exchange=creation.create(a.id(),cmd);
        var before=businessRows();
        call("PATCH","/api/demands/"+demand,b.token(),Map.of("version",0,"description","changed"),409);
        call("PATCH","/api/demands/"+demand+"/status",b.token(),Map.of("version",0,"status","INACTIVE"),409);
        call("DELETE","/api/demands/"+demand+"?version=0",b.token(),null,409);
        assertEquals(before,businessRows());
        call("PATCH","/api/demands/"+other,b.token(),Map.of("version",0,"description","unselected remains editable"),200);
        String saved=jdbc.queryForObject("SELECT demand_snapshot FROM cl_exchange_demand WHERE exchange_id=? AND demand_id=?",String.class,exchange,demand);
        jdbc.update("UPDATE cl_exchange SET status='COMPLETED' WHERE id=?",exchange); // Explicit terminal fixture, not a completion implementation.
        call("PATCH","/api/demands/"+demand,b.token(),Map.of("version",0,"description","later edit"),200);
        assertEquals(saved,jdbc.queryForObject("SELECT demand_snapshot FROM cl_exchange_demand WHERE exchange_id=? AND demand_id=?",String.class,exchange,demand));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("DELETE FROM cl_demand WHERE id=?",demand));
    }

    @Test void betterUnselectedDemandInvalidatesOldRecommendationAndCreatorMustParticipate() throws Exception {
        var cmd=ring(2,"choice-stale-key");
        long x=cmd.flows().get(0).itemId(), y=cmd.flows().get(1).itemId();
        rejected(403,()->creation.create(outsider.id(),cmd));
        call("POST","/api/exchanges",outsider.token(),cmd,403);
        jdbc.update("UPDATE cl_item SET tags_json='[\"new\"]' WHERE id=?",x);
        long alternative=demand(b,1,y);
        jdbc.update("UPDATE cl_demand SET preferred_tags_json='[\"new\"]' WHERE id=?",alternative);
        var before=businessRows();
        rejected(409,()->creation.create(a.id(),cmd));
        assertEquals(before,businessRows(),"Must reselect from ALL active associated demands");
    }

    @Test void realUniqueConstraintFailureAfterParticipantOrHoldInsertionRollsBackEveryRow() {
        for(String stage:List.of("insert","participant","demand","hold","reserve")) {
            var cmd=ring(3,"rollback-"+stage);var before=businessRows();
            var once=new java.util.concurrent.atomic.AtomicBoolean();
            SqlProbe.after.set(statement -> {
                if(statement.endsWith("ExchangeCreationMapper."+stage) && once.compareAndSet(false,true)) {
                    if(stage.equals("insert")) jdbc.update("INSERT INTO cl_exchange(initiator_id,status,idempotency_key,expires_at) SELECT initiator_id,status,idempotency_key,expires_at FROM cl_exchange WHERE initiator_id=? AND idempotency_key=?",a.id(),cmd.idempotencyKey());
                    else if(stage.equals("demand")) jdbc.update("INSERT INTO cl_exchange_demand(exchange_id,demand_id,offered_item_id,demand_version,demand_snapshot) SELECT exchange_id,demand_id,offered_item_id,demand_version,demand_snapshot FROM cl_exchange_demand WHERE demand_id=?",cmd.flows().get(0).demandId());
                    else if(stage.equals("participant")) jdbc.update("INSERT INTO cl_exchange_participant(exchange_id,user_id,offered_item_id,recipient_user_id) SELECT exchange_id,user_id,offered_item_id,recipient_user_id FROM cl_exchange_participant WHERE offered_item_id=?",cmd.flows().get(0).itemId());
                    else jdbc.update("INSERT INTO cl_item_hold(item_id,exchange_id,expires_at) SELECT item_id,exchange_id,expires_at FROM cl_item_hold WHERE item_id=?",cmd.flows().get(0).itemId());
                }
            });
            try { rejected(409,()->creation.create(a.id(),cmd)); }
            finally { SqlProbe.after.remove(); }
            assertTrue(once.get(),"Failure injection must occur after an actual database write");
            assertEquals(before,businessRows(),"Constraint failure must roll back exchange, participants, references, holds and item version");
            assertTrue(creation.create(a.id(),cmd)>0,"Failed transaction must not consume the idempotency key");
        }
    }

    @Test void mysqlCompetingPlansWithSameItemHaveExactlyOneWinner() throws Exception {
        mysqlOnly();
        var first=ring(2,"competing-first");
        long x=first.flows().get(0).itemId(),da=first.flows().get(1).demandId();
        long z=item(c,2),dc=demand(c,1,z);
        var second=new ExchangeCreationCommand("independent-v2","competing-second",List.of(flow(x,0,dc,0),flow(z,0,da,0)));
        var outcome=race(()->creation.create(b.id(),first),()->creation.create(c.id(),second));
        assertTrue(outcome.get(0)>0);assertEquals(-409L,outcome.get(1));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold WHERE item_id=?",Integer.class,x));
        assertEquals("AVAILABLE",jdbc.queryForObject("SELECT status FROM cl_item WHERE id=?",String.class,z));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange_participant WHERE offered_item_id=?",Integer.class,z));
    }

    @Test void mysqlConcurrentSameRequestReplaysAndConcurrentDifferentContentConflicts() throws Exception {
        mysqlOnly();
        var same=ring(2,"concurrent-retry");
        var replay=race(()->creation.create(a.id(),same),()->creation.create(a.id(),same));
        assertEquals(replay.get(0),replay.get(1));
        var first=ring(2,"concurrent-other");var second=ring(2,"concurrent-other");
        var conflict=race(()->creation.create(a.id(),first),()->creation.create(a.id(),second));
        assertTrue(conflict.get(0)>0);assertEquals(-409L,conflict.get(1));
    }

    @Test void mysqlCreationWaitsOnActualItemLockAndRejectsCommittedWithdrawal() throws Exception {
        mysqlOnly();var cmd=ring(2,"locked-item-key");long item=cmd.flows().get(0).itemId();
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        var reached=new java.util.concurrent.CountDownLatch(1);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Long>> future=new java.util.concurrent.atomic.AtomicReference<>();
        try {
            tx.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM cl_item WHERE id=? FOR UPDATE",item);
                future.set(pool.submit(()-> {
                    SqlProbe.before.set(id -> { if(id.endsWith("ItemMapper.selectForUpdate")) reached.countDown(); });
                    try {return result(()->creation.create(a.id(),cmd));} finally {SqlProbe.before.remove();}
                }));
                await(reached);assertThrows(java.util.concurrent.TimeoutException.class,()->future.get().get(250,java.util.concurrent.TimeUnit.MILLISECONDS));
                jdbc.update("UPDATE cl_item SET status='HIDDEN',version=version+1 WHERE id=?",item);
            });
            assertEquals(-409L,future.get().get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange WHERE initiator_id=?",Integer.class,a.id()));
        } finally { pool.shutdownNow(); }
    }

    @Test void mysqlDemandMutationWaitsForCreationThenObservesFreeze() throws Exception {
        mysqlOnly();var cmd=ring(2,"freeze-race-key");long demand=cmd.flows().get(0).demandId();
        var result=race(()->creation.create(a.id(),cmd),()-> {
            demandWrites.patch(b.id(),demand,new edu.campusloop.web.demand.dto.PatchDemandRequest(0,null,"changed",null,null));return 1L;
        });
        assertTrue(result.get(0)>0);assertEquals(-409L,result.get(1));
        assertEquals(0,jdbc.queryForObject("SELECT version FROM cl_demand WHERE id=?",Integer.class,demand));
    }

    @Test void mysqlNewAndUnselectedDemandWritesCannotChangeTheLockedSelection() throws Exception {
        mysqlOnly();
        for(boolean insert:List.of(true,false)) {
            var cmd=ring(2,"selection-race-"+insert);long y=cmd.flows().get(1).itemId();
            long alternative=demand(b,1,y);
            var result=race(()->creation.create(a.id(),cmd),()-> {
                if(insert) demandWrites.create(b.id(),new edu.campusloop.web.demand.dto.CreateDemandRequest(1L,"new",List.of("new"),List.of(y)));
                else demandWrites.patch(b.id(),alternative,new edu.campusloop.web.demand.dto.PatchDemandRequest(0,null,null,List.of("new"),null));
                return 1L;
            });
            assertTrue(result.get(0)>0);assertEquals(insert?-409L:1L,result.get(1));
            assertEquals(cmd.flows().get(0).demandId(),jdbc.queryForObject("SELECT demand_id FROM cl_exchange_demand WHERE exchange_id=? AND offered_item_id=?",Long.class,result.get(0),y));
        }
    }

    @Test void mysqlTransientFailuresRetryOnlyWholeTransactionsWithABoundedBudget() throws Exception {
        mysqlOnly();
        for(int failures:List.of(1,3)) {
            var cmd=ring(2,"retry-budget-"+failures);var before=businessRows();
            var attempts=new java.util.concurrent.atomic.AtomicInteger();
            SqlProbe.after.set(statement -> {
                if(statement.endsWith("ExchangeCreationMapper.reserve") && attempts.getAndIncrement()<failures)
                    jdbc.execute("SIGNAL SQLSTATE '40001' SET MYSQL_ERRNO=1213, MESSAGE_TEXT='isolated test transient conflict'");
            });
            try {
                if(failures==1) assertTrue(creation.create(a.id(),cmd)>0);
                else {rejected(409,()->creation.create(a.id(),cmd));assertEquals(before,businessRows());assertEquals(3,attempts.get());}
            } finally {SqlProbe.after.remove();}
            long id=creation.create(a.id(),cmd);
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange_participant WHERE exchange_id=?",Integer.class,id));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold WHERE exchange_id=?",Integer.class,id));
        }
    }

    private ExchangeCreationCommand ring(int length,String key) {
        List<Account> people=List.of(a,b,c).subList(0,length);List<Long> items=new ArrayList<>(),demands=new ArrayList<>();
        for(int i=0;i<length;i++) items.add(item(people.get(i),i+1));
        for(int i=0;i<length;i++) demands.add(demand(people.get(i),(i+length-1)%length+1,items.get(i)));
        List<ExchangeCreationCommand.ExpectedFlow> flows=new ArrayList<>();
        for(int i=0;i<length;i++) flows.add(flow(items.get(i),0,demands.get((i+1)%length),0));
        return new ExchangeCreationCommand("independent-v2",key,flows);
    }
    private void mysqlOnly() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("campus.mysql-test"),"Row-lock evidence requires isolated MySQL");
        try(var connection=jdbc.getDataSource().getConnection()) {assertEquals("MySQL",connection.getMetaData().getDatabaseProductName());}
    }
    private static long result(java.util.concurrent.Callable<Long> action) throws Exception {
        try {return action.call();} catch(ApiException conflict) {return -conflict.getStatus();}
    }
    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {assertTrue(latch.await(10,java.util.concurrent.TimeUnit.SECONDS));}
        catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
    }
    /** Pause the real first transaction after all locks/validation, then prove the rival blocks in user FOR UPDATE. */
    private List<Long> race(java.util.concurrent.Callable<Long> first,java.util.concurrent.Callable<Long> second) throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var waiting=new java.util.concurrent.CountDownLatch(1);
        try {
            var one=pool.submit(()-> {
                SqlProbe.before.set(id -> {if(id.endsWith("ExchangeCreationMapper.insert")){locked.countDown();await(release);}});
                try{return result(first);}finally{SqlProbe.before.remove();}
            });
            await(locked);
            var two=pool.submit(()-> {
                SqlProbe.before.set(id -> {if(id.endsWith("UserMapper.selectByIdForUpdate"))waiting.countDown();});
                try{return result(second);}finally{SqlProbe.before.remove();}
            });
            await(waiting);assertThrows(java.util.concurrent.TimeoutException.class,()->two.get(250,java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();return List.of(one.get(10,java.util.concurrent.TimeUnit.SECONDS),two.get(10,java.util.concurrent.TimeUnit.SECONDS));
        } finally {release.countDown();pool.shutdownNow();}
    }

    private void assertStaleWithoutWrites(ExchangeCreationCommand cmd,long x,long y) {
        var before=businessRows();rejected(409,()->new ExchangeCycleValidator().validate(a.id(),cmd,candidates.snapshot(List.of(x,y))));rejected(409,()->creation.create(a.id(),cmd));assertEquals(before,businessRows());
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
        values.put("cl_exchange_demand",jdbc.queryForList("SELECT * FROM cl_exchange_demand ORDER BY exchange_id,demand_id"));
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
