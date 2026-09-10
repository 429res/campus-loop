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
    private static final java.nio.file.Path TEST_UPLOADS=temporaryUploads();
    private static java.nio.file.Path temporaryUploads() {
        try{return java.nio.file.Files.createTempDirectory("campus-b05-public-test-");}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }
    @AfterAll static void removeTestUploadDirectories() throws Exception {
        java.nio.file.Files.deleteIfExists(TEST_UPLOADS);
        java.nio.file.Files.deleteIfExists(TEST_UPLOADS.resolveSibling(TEST_UPLOADS.getFileName()+"-evidence"));
    }
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
        registry.add("campus.upload-dir",()->TEST_UPLOADS.toString());
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
    @AfterEach void removeOnlyOwnFixtures() throws Exception {
        List<Long> histories=new ArrayList<>();
        for(long user:users) histories.addAll(jdbc.queryForList("SELECT id FROM cl_item_history WHERE source_user_id=?",Long.class,user));
        for(long id:histories.stream().distinct().sorted(Comparator.reverseOrder()).toList()) {
            for(String table:List.of("cl_history_verification_audit","cl_history_verification_state")) jdbc.update("DELETE FROM "+table+" WHERE history_id=?",id);
            for(String table:List.of("cl_history_confirmation_withdrawal","cl_history_confirmation","cl_history_confirmation_member","cl_history_confirmation_request")) jdbc.update("DELETE FROM "+table+" WHERE history_id=?",id);
            jdbc.update("DELETE FROM cl_history_evidence WHERE history_id=?",id);
            jdbc.update("DELETE FROM cl_item_history WHERE id=?",id);
        }
        for(long user:users) {
            for(String id:jdbc.queryForList("SELECT id FROM cl_upload WHERE owner_id=?",String.class,user)) {
                java.nio.file.Files.deleteIfExists(TEST_UPLOADS.resolve(id+".png"));
                java.nio.file.Files.deleteIfExists(TEST_UPLOADS.resolveSibling(TEST_UPLOADS.getFileName()+"-evidence").resolve(id+".png"));
            }
            jdbc.update("DELETE FROM cl_upload WHERE owner_id=?",user);
        }
        for(long user:users) exchanges.addAll(jdbc.queryForList("SELECT id FROM cl_exchange WHERE initiator_id=?",Long.class,user));
        for(long exchange:new LinkedHashSet<>(exchanges)) {
            jdbc.update("DELETE FROM cl_item_history WHERE exchange_id=?",exchange);
            jdbc.update("DELETE FROM cl_item_hold WHERE exchange_id=?",exchange);
            jdbc.update("DELETE FROM cl_exchange_event WHERE exchange_id=?",exchange);
            jdbc.update("DELETE FROM cl_exchange_demand WHERE exchange_id=?",exchange);
            jdbc.update("DELETE FROM cl_exchange_participant WHERE exchange_id=?",exchange);
            jdbc.update("DELETE FROM cl_exchange WHERE id=?",exchange);
        }
        for(long user:users) {
            jdbc.update("DELETE FROM cl_demand_item WHERE demand_id IN (SELECT id FROM cl_demand WHERE owner_id=?)",user);
            jdbc.update("DELETE FROM cl_demand WHERE owner_id=?",user);
        }
        for(long user:users) {
            jdbc.update("DELETE FROM cl_item WHERE owner_id=?",user);
            jdbc.update("DELETE FROM cl_notification WHERE user_id=?",user);
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
        call("GET","/api/admin/exchanges",a.token(),null,403);
        var adminRows=call("GET","/api/admin/exchanges",admin.token(),null,200).path("records");
        assertEquals(1,adminRows.size());assertEquals(id,adminRows.get(0).path("id").asLong());
        assertEquals(0,adminRows.get(0).path("allowedActions").size());
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
        call("POST","/api/exchanges/1/handoff",admin.token(),Map.of("version",0),400);
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

    @Autowired ExchangeLifecycleService lifecycle;
    @Autowired ExchangeDatabaseClock exchangeClock;

    @Test void realTwoAndThreePartyInvitationsReachReadyOnlyAfterAllParticipantsConfirm() throws Exception {
        for(int length:List.of(2,3)) {
            long id=call("POST","/api/exchanges",a.token(),ring(length,"invitation-"+length),200).path("id").asLong();
            for(int i=0;i<length;i++) {
                Account actor=List.of(a,b,c).get(i);
                var before=call("GET","/api/exchanges/"+id,actor.token(),null,200);
                assertEquals(json.readTree("[\"CONFIRM\",\"CANCEL\"]"),before.path("allowedActions"));
                var result=call("POST","/api/exchanges/"+id+"/confirm",actor.token(),Map.of("version",i),200);
                assertEquals(i+1,result.path("version").asInt());
                assertEquals(i==length-1?"READY":"AWAITING_CONFIRMATION",result.path("status").asText());
                assertEquals(json.readTree(i==length-1?"[\"HANDED_OFF\",\"RECEIVED\",\"CANCEL\"]":"[\"CANCEL\"]"),result.path("allowedActions"));
                var rows=businessRows();
                assertEquals(result,call("POST","/api/exchanges/"+id+"/confirm",actor.token(),Map.of("version",i),200));
                assertEquals(rows,businessRows());
            }
            assertEquals(length,eventCount(id));
            assertEquals(length,jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange_participant WHERE exchange_id=? AND confirmed_at IS NOT NULL",Integer.class,id));
            assertEquals(length,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold WHERE exchange_id=?",Integer.class,id));
            assertEquals(1,call("GET","/api/exchanges/mine?status=READY",a.token(),null,200).path("records").findValues("id").stream().filter(n->n.asLong()==id).count());
        }
    }

    @Test void waitingAndReadyCancellationRecordsActorAndReleasesOnlyItsOwnItems() throws Exception {
        for(boolean ready:List.of(false,true)) {
            var command=ring(2,"cancel-state-"+ready);long id=creation.create(a.id(),command);int version=0;
            if(ready) {lifecycle.confirm(a.id(),id,0);lifecycle.confirm(b.id(),id,1);version=2;}
            var cancelled=call("POST","/api/exchanges/"+id+"/cancel",b.token(),Map.of("version",version,"reason","  课程时间冲突  "),200);
            assertEquals("CANCELLED",cancelled.path("status").asText());assertEquals(b.id(),cancelled.path("cancelledBy").asLong());
            assertEquals("课程时间冲突",cancelled.path("cancellationReason").asText());assertTrue(cancelled.path("cancelledAt").asText().endsWith("Z"));
            assertTrue(cancelled.path("allowedActions").isEmpty());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold WHERE exchange_id=?",Integer.class,id));
            assertEquals(version+1,eventCount(id));
            for(var flow:command.flows()) {
                var item=jdbc.queryForMap("SELECT owner_id,status,version,review_basis FROM cl_item WHERE id=?",flow.itemId());
                assertEquals("AVAILABLE",item.get("status"));assertEquals(2,((Number)item.get("version")).intValue());assertEquals("LEGACY_DIRECT",item.get("review_basis"));
            }
            assertEquals(a.id(),jdbc.queryForObject("SELECT owner_id FROM cl_item WHERE id=?",Long.class,command.flows().get(0).itemId()));
            var oldExpiry=jdbc.queryForObject("SELECT expires_at FROM cl_exchange WHERE id=?",java.time.LocalDateTime.class,id);
            jdbc.update("UPDATE cl_exchange SET expires_at=? WHERE id=?",exchangeClock.now().minusSeconds(1),id);
            var rows=businessRows();
            call("POST","/api/exchanges/"+id+"/cancel",b.token(),Map.of("version",version,"reason","课程时间冲突"),200);
            call("POST","/api/exchanges/"+id+"/cancel",a.token(),Map.of("version",version,"reason","课程时间冲突"),409);
            call("POST","/api/exchanges/"+id+"/cancel",b.token(),Map.of("version",version,"reason","不同原因"),409);
            assertEquals(rows,businessRows());
            jdbc.update("UPDATE cl_exchange SET expires_at=? WHERE id=?",oldExpiry,id);
            call("PATCH","/api/demands/"+command.flows().get(0).demandId(),b.token(),Map.of("version",0,"description","取消后可以编辑"),200);
        }
    }

    @Test void invitationEndpointsRejectImpersonationMalformedCommandsAndStaleVersions() throws Exception {
        long id=creation.create(a.id(),ring(2,"action-auth"));var rows=businessRows();
        for(String action:List.of("confirm","cancel")) {
            Object body=action.equals("confirm")?Map.of("version",0):Map.of("version",0,"reason","原因");
            call("POST","/api/exchanges/"+id+"/"+action,null,body,401);
            for(var stranger:List.of(outsider,admin)) call("POST","/api/exchanges/"+id+"/"+action,stranger.token(),body,404);
            call("POST","/api/exchanges/"+id+"/"+action+"?actorId="+b.id(),a.token(),body,400);
            call("POST","/api/exchanges/0/"+action,a.token(),body,400);
        }
        for(String body:List.of("{}","{\"version\":null}","{\"version\":\"0\"}","{\"version\":0.0}","{\"version\":-1}","{\"version\":2147483648}","{\"version\":0,\"actorId\":1}"))
            call("POST","/api/exchanges/"+id+"/confirm",a.token(),json.readTree(body),400);
        for(Object body:List.of(Map.of("version",0),Map.of("version",0,"reason"," "),Map.of("version",0,"reason",10),Map.of("version",0,"reason","a".repeat(1001)),Map.of("version",0,"reason","原因","status","CANCELLED")))
            call("POST","/api/exchanges/"+id+"/cancel",a.token(),body,400);
        assertEquals(rows,businessRows());
        lifecycle.confirm(a.id(),id,0);rows=businessRows();
        call("POST","/api/exchanges/"+id+"/confirm",b.token(),Map.of("version",0),409);
        call("POST","/api/exchanges/"+id+"/confirm",a.token(),Map.of("version",2),409);
        call("POST","/api/exchanges/"+id+"/cancel",a.token(),Map.of("version",0,"reason","原因"),409);
        assertEquals(rows,businessRows());
    }

    @Test void terminalLegacyAndHandoverRecordsCannotBeMutatedOrReleased() throws Exception {
        for(String state:List.of("COMPLETED","CANCELLED","EXPIRED","DISPUTED")) {
            long id=creation.create(a.id(),ring(2,"terminal-"+state.toLowerCase(java.util.Locale.ROOT)));jdbc.update("UPDATE cl_exchange SET status=? WHERE id=?",state,id);
            var rows=businessRows();rejected(409,()->lifecycle.confirm(a.id(),id,0));rejected(409,()->lifecycle.cancel(a.id(),id,0,"原因"));
            assertFalse(lifecycle.expire(id));assertTrue(call("GET","/api/exchanges/"+id,a.token(),null,200).path("allowedActions").isEmpty());assertEquals(rows,businessRows());
        }
        long id=creation.create(a.id(),ring(2,"handover-protect"));lifecycle.confirm(a.id(),id,0);lifecycle.confirm(b.id(),id,1);
        jdbc.update("UPDATE cl_exchange_participant SET handed_off_at=CURRENT_TIMESTAMP WHERE exchange_id=? AND user_id=?",id,a.id());
        jdbc.update("UPDATE cl_exchange SET expires_at=? WHERE id=?",exchangeClock.now().minusSeconds(1),id);
        var rows=businessRows();rejected(409,()->lifecycle.cancel(a.id(),id,2,"原因"));rejected(409,()->lifecycle.confirm(b.id(),id,2));assertFalse(lifecycle.expire(id));
        assertEquals(json.readTree("[\"RECEIVED\",\"DISPUTE\"]"),call("GET","/api/exchanges/"+id,a.token(),null,200).path("allowedActions"));assertEquals(rows,businessRows());
        long legacy=seedStoredExchange(List.of(a,b),List.of(item(a,1),item(b,2)),"AWAITING_CONFIRMATION");
        rejected(409,()->lifecycle.confirm(a.id(),legacy,0));rejected(409,()->lifecycle.cancel(a.id(),legacy,0,"原因"));
        assertTrue(call("GET","/api/exchanges/"+legacy,a.token(),null,200).path("allowedActions").isEmpty());
    }

    @Test void databaseDeadlineAndCommonExpiryEntryNeverWriteOnReadOrRepeatRelease() throws Exception {
        long id=creation.create(a.id(),ring(2,"common-expiry"));assertFalse(lifecycle.expire(id));
        jdbc.update("UPDATE cl_exchange SET expires_at=? WHERE id=?",exchangeClock.now(),id);
        var rows=businessRows();
        assertTrue(call("GET","/api/exchanges/"+id,a.token(),null,200).path("allowedActions").isEmpty());
        call("POST","/api/exchanges/"+id+"/confirm",a.token(),Map.of("version",0),409);
        call("POST","/api/exchanges/"+id+"/cancel",a.token(),Map.of("version",0,"reason","原因"),409);
        assertEquals(rows,businessRows());assertTrue(lifecycle.expire(id));rows=businessRows();assertFalse(lifecycle.expire(id));assertEquals(rows,businessRows());
        assertEquals("EXPIRED",jdbc.queryForObject("SELECT status FROM cl_exchange WHERE id=?",String.class,id));assertEquals(1,eventCount(id));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold WHERE exchange_id=?",Integer.class,id));
        assertNull(jdbc.queryForObject("SELECT actor_id FROM cl_exchange_event WHERE exchange_id=?",Long.class,id));
    }

    @Test void foreignHoldsAndChangedOfferFactsFailWithoutPartialWrites() {
        for(String problem:List.of("hold","version","owner","status")) {
            var command=ring(2,"protect-"+problem);long id=creation.create(a.id(),command);
            long other=creation.create(a.id(),ring(2,"other-"+problem));long item=command.flows().get(1).itemId();
            switch(problem) {
                case "hold" -> jdbc.update("UPDATE cl_item_hold SET exchange_id=? WHERE item_id=?",other,item);
                case "version" -> jdbc.update("UPDATE cl_item SET version=version+1 WHERE id=?",item);
                case "owner" -> jdbc.update("UPDATE cl_item SET owner_id=? WHERE id=?",c.id(),item);
                case "status" -> jdbc.update("UPDATE cl_item SET status='HIDDEN' WHERE id=?",item);
            }
            var rows=businessRows();rejected(409,()->lifecycle.cancel(a.id(),id,0,"原因"));rejected(409,()->lifecycle.confirm(a.id(),id,0));assertEquals(rows,businessRows());
        }
    }

    @Test void lifecycleConstraintFailuresAfterEachWriteRollBackTheWholeTransaction() {
        for(String stage:List.of("confirm","transition","event","release","restore")) {
            long id=creation.create(a.id(),ring(3,"lifecycle-failure-"+stage));var rows=businessRows();
            var once=new java.util.concurrent.atomic.AtomicBoolean();
            SqlProbe.after.set(statement -> {
                if(statement.endsWith("ExchangeLifecycleMapper."+stage) && once.compareAndSet(false,true))
                    jdbc.update("INSERT INTO cl_exchange(initiator_id,status,idempotency_key,expires_at) SELECT initiator_id,status,idempotency_key,expires_at FROM cl_exchange WHERE id=?",id);
            });
            try { rejected(409,()-> {if(stage.equals("confirm")) lifecycle.confirm(a.id(),id,0);else lifecycle.cancel(b.id(),id,0,"原因");}); }
            finally {SqlProbe.after.remove();}
            assertTrue(once.get());assertEquals(rows,businessRows(),"Participant/state/audit/release/restore must all roll back");
            lifecycle.cancel(b.id(),id,0,"原因");assertEquals(1,eventCount(id));
        }
    }

    @Test void mysqlConfirmCancelAndDuplicateCancelSerializeOnTheSameExchangeLock() throws Exception {
        mysqlOnly();
        long first=creation.create(a.id(),ring(2,"confirm-cancel-race"));
        var result=race(()->lifecycle.confirm(a.id(),first,0),()->lifecycle.cancel(b.id(),first,0,"原因"),"ExchangeLifecycleMapper.transition","ExchangeLifecycleMapper.lock");
        assertEquals(List.of(first,-409L),result);assertEquals(1,eventCount(first));lifecycle.cancel(b.id(),first,1,"原因");
        long second=creation.create(a.id(),ring(2,"cancel-confirm-race"));
        result=race(()->lifecycle.cancel(a.id(),second,0,"原因"),()->lifecycle.confirm(b.id(),second,0),"ExchangeLifecycleMapper.transition","ExchangeLifecycleMapper.lock");
        assertEquals(List.of(second,-409L),result);assertEquals(1,eventCount(second));
        long retry=creation.create(a.id(),ring(2,"cancel-retry-race"));
        result=race(()->lifecycle.cancel(b.id(),retry,0,"原因"),()->lifecycle.cancel(b.id(),retry,0," 原因 "),"ExchangeLifecycleMapper.transition","ExchangeLifecycleMapper.lock");
        assertEquals(List.of(retry,retry),result);assertEquals(1,eventCount(retry));
    }

    @Test void mysqlLockWaitPastDeadlineRejectsConfirmCancelAndFirstHandoffUsingTimeAfterAllLocks() throws Exception {
        mysqlOnly();
        for(String action:List.of("confirm","cancel","handoff")) {
            var command=ring(2,"deadline-lock-"+action);long id=creation.create(a.id(),command);
            if(action.equals("handoff")) {lifecycle.confirm(a.id(),id,0);lifecycle.confirm(b.id(),id,1);}
            long item=command.flows().get(0).itemId();
            var deadline=exchangeClock.now().plusSeconds(2);jdbc.update("UPDATE cl_exchange SET expires_at=? WHERE id=?",deadline,id);
            var rows=businessRows();var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
            var reached=new java.util.concurrent.CountDownLatch(1);
            var observed=new java.util.concurrent.atomic.AtomicBoolean();
            var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
            var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Long>>();
            try {
                tx.executeWithoutResult(status -> {
                    jdbc.queryForList("SELECT id FROM cl_item WHERE id=? FOR UPDATE",item);
                    future.set(pool.submit(()-> {
                        SqlProbe.before.set(statement -> {if(statement.endsWith("ItemMapper.selectForUpdate") && observed.compareAndSet(false,true)){assertTrue(exchangeClock.now().isBefore(deadline));reached.countDown();}});
                        try{return result(()->switch(action) {case "cancel" -> lifecycle.cancel(a.id(),id,0,"原因");case "handoff" -> lifecycle.handoff(a.id(),id,2,GIVEN,"");default -> lifecycle.confirm(a.id(),id,0);});}finally{SqlProbe.before.remove();}
                    }));
                    await(reached);assertThrows(java.util.concurrent.TimeoutException.class,()->future.get().get(250,java.util.concurrent.TimeUnit.MILLISECONDS));
                    jdbc.queryForObject("SELECT SLEEP(2.1)",Integer.class);
                });
                assertEquals(-409L,future.get().get(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(rows,businessRows());
            } finally {pool.shutdownNow();}
        }
    }

    @Test void mysqlCancellationAndExpiryCannotReleaseTwiceOrResurrectState() throws Exception {
        mysqlOnly();long cancelled=creation.create(a.id(),ring(2,"cancel-expiry-race"));
        var result=race(()->lifecycle.cancel(a.id(),cancelled,0,"原因"),()->lifecycle.expire(cancelled)?1L:0L,"ExchangeLifecycleMapper.transition","ExchangeLifecycleMapper.lock");
        assertEquals(List.of(cancelled,0L),result);assertEquals(1,eventCount(cancelled));
        long expired=creation.create(a.id(),ring(2,"expiry-cancel-race"));jdbc.update("UPDATE cl_exchange SET expires_at=? WHERE id=?",exchangeClock.now(),expired);
        result=race(()->lifecycle.expire(expired)?1L:0L,()->lifecycle.cancel(b.id(),expired,0,"原因"),"ExchangeLifecycleMapper.transition","ExchangeLifecycleMapper.lock");
        assertEquals(List.of(1L,-409L),result);assertEquals(1,eventCount(expired));
    }

    @Autowired ExchangeExpiryScanner expiryScanner;
    @Autowired edu.campusloop.web.exchange.mapper.ExchangeExpiryMapper expiryQueue;
    @Autowired ExchangeTransactionExecutor exchangeTransactions;

    @Test void scannerUsesDatabaseDeadlineAndOnlyExpiresOpenUnhandedExchanges() throws Exception {
        long pending=creation.create(a.id(),ring(2,"scan-pending"));
        long ready=creation.create(a.id(),ring(3,"scan-ready"));
        lifecycle.confirm(a.id(),ready,0);lifecycle.confirm(b.id(),ready,1);lifecycle.confirm(c.id(),ready,2);
        long future=creation.create(a.id(),ring(2,"scan-future"));
        jdbc.update("UPDATE cl_item_hold SET expires_at=? WHERE exchange_id=?",exchangeClock.now().minusDays(1),future);
        List<Long> protectedIds=new ArrayList<>();
        for(String state:List.of("COMPLETED","CANCELLED","EXPIRED","DISPUTED")) {
            long id=creation.create(a.id(),ring(2,"scan-"+state.toLowerCase(Locale.ROOT)));
            jdbc.update("UPDATE cl_exchange SET status=? WHERE id=?",state,id);due(id);protectedIds.add(id);
        }
        for(String handover:List.of("handed_off_at","received_at")) {
            long id=creation.create(a.id(),ring(2,"scan-"+handover));due(id);
            jdbc.update("UPDATE cl_exchange_participant SET "+handover+"=? WHERE exchange_id=? AND user_id=?",exchangeClock.now(),id,a.id());protectedIds.add(id);
        }
        long legacy=seedStoredExchange(List.of(a,b),List.of(item(a,1),item(b,2)),"AWAITING_CONFIRMATION");due(legacy);protectedIds.add(legacy);
        due(pending);due(ready);
        var protectedRows=jdbc.queryForList("SELECT * FROM cl_item_hold WHERE exchange_id NOT IN (?,?) ORDER BY item_id",pending,ready);
        var batch=expiryScanner.scanBatch();assertEquals(2,batch.selected());assertEquals(2,batch.expired());assertEquals(0,batch.deferred());
        assertEquals("EXPIRED",state(pending));assertEquals("EXPIRED",state(ready));assertEquals("AWAITING_CONFIRMATION",state(future));
        assertEquals(protectedRows,jdbc.queryForList("SELECT * FROM cl_item_hold WHERE exchange_id NOT IN (?,?) ORDER BY item_id",pending,ready));
        var after=businessRows();assertEquals(0,expiryScanner.scanBatch().selected());assertEquals(after,businessRows());
        assertEquals(1,eventCount(pending));assertEquals(4,eventCount(ready));
        assertTrue(call("GET","/api/exchanges/"+ready,a.token(),null,200).path("allowedActions").isEmpty());
    }

    @Test void boundedBatchesAndPersistedBackoffPreventPoisonRecordFromBlockingLaterWork() {
        var small=new ExchangeExpiryScanner(expiryQueue,lifecycle,exchangeClock,exchangeTransactions,1);
        long bad=creation.create(a.id(),ring(2,"batch-bad"));due(bad);
        jdbc.update("UPDATE cl_item SET version=version+1 WHERE id=(SELECT offered_item_id FROM cl_exchange_participant WHERE exchange_id=? AND user_id=?)",bad,a.id());
        long good=creation.create(a.id(),ring(2,"batch-good"));due(good);
        var before=businessWithoutRetry();var now=exchangeClock.now();
        var failed=small.scanBatch();assertEquals(1,failed.selected());assertEquals(1,failed.deferred());assertEquals(0,failed.retryWriteFailures());
        assertEquals(before,businessWithoutRetry());assertEquals(1,retryCount(bad));
        var retry=jdbc.queryForObject("SELECT expiry_retry_at FROM cl_exchange WHERE id=?",java.time.LocalDateTime.class,bad);
        assertFalse(retry.isBefore(now.plusSeconds(30)));assertFalse(retry.isAfter(exchangeClock.now().plusSeconds(30)));
        assertEquals("STATE_CONFLICT",jdbc.queryForObject("SELECT expiry_failure_code FROM cl_exchange WHERE id=?",String.class,bad));
        assertEquals(1,small.scanBatch().expired());assertEquals("EXPIRED",state(good));assertEquals(0,small.scanBatch().selected());
        jdbc.update("UPDATE cl_exchange SET expiry_retry_at=? WHERE id=?",exchangeClock.now(),bad);
        now=exchangeClock.now();assertEquals(1,small.scanBatch().deferred());assertEquals(2,retryCount(bad));
        assertFalse(jdbc.queryForObject("SELECT expiry_retry_at FROM cl_exchange WHERE id=?",java.time.LocalDateTime.class,bad).isBefore(now.plusSeconds(60)));
    }

    @Test void failedExpiryAfterEachWriteRollsBackAndNextAttemptRecoversExactlyOnce() {
        for(String stage:List.of("transition","event","release","restore")) {
            long id=creation.create(a.id(),ring(3,"expiry-fail-"+stage));due(id);var before=businessWithoutRetry();
            var once=new java.util.concurrent.atomic.AtomicBoolean();
            SqlProbe.after.set(statement -> {
                if(statement.endsWith("ExchangeLifecycleMapper."+stage) && once.compareAndSet(false,true))
                    jdbc.update("INSERT INTO cl_exchange(initiator_id,status,idempotency_key,expires_at) SELECT initiator_id,status,idempotency_key,expires_at FROM cl_exchange WHERE id=?",id);
            });
            try {var batch=expiryScanner.scanBatch();assertEquals(1,batch.deferred());assertEquals(0,batch.expired());}
            finally {SqlProbe.after.remove();}
            assertTrue(once.get());assertEquals(before,businessWithoutRetry());assertEquals(1,retryCount(id));assertEquals(0,eventCount(id));
            jdbc.update("UPDATE cl_exchange SET expiry_retry_at=? WHERE id=?",exchangeClock.now(),id);
            assertEquals(1,expiryScanner.scanBatch().expired());assertEquals(1,eventCount(id));assertEquals("EXPIRED",state(id));
            assertNull(jdbc.queryForObject("SELECT expiry_failure_code FROM cl_exchange WHERE id=?",String.class,id));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold WHERE exchange_id=?",Integer.class,id));
            var after=businessRows();assertFalse(lifecycle.expireForScan(id));assertEquals(after,businessRows());
        }
    }

    @Test void expiryNeverReleasesForeignOrNewExchangeHolds() {
        var cmd=ring(2,"expiry-foreign");long bad=creation.create(a.id(),cmd);due(bad);
        long other=creation.create(a.id(),ring(2,"expiry-other"));long item=cmd.flows().get(1).itemId();
        jdbc.update("UPDATE cl_item_hold SET exchange_id=? WHERE item_id=?",other,item);
        var before=businessWithoutRetry();assertEquals(1,expiryScanner.scanBatch().deferred());assertEquals(before,businessWithoutRetry());
        var old=ring(2,"expiry-old");long oldId=creation.create(a.id(),old);lifecycle.cancel(a.id(),oldId,0,"换个方案");
        var next=new ExchangeCreationCommand(old.ruleVersion(),"expiry-new",old.flows().stream()
            .map(f -> flow(f.itemId(),f.itemVersion()+2,f.demandId(),f.demandVersion())).toList());
        long newId=creation.create(a.id(),next);before=businessRows();
        assertFalse(lifecycle.expireForScan(oldId));assertEquals(before,businessRows());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold WHERE exchange_id=?",Integer.class,newId));
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void retryPersistenceFailureLeavesWorkRecoverableAndLogsNoExceptionPayload(org.springframework.boot.test.system.CapturedOutput output) {
        long id=creation.create(a.id(),ring(2,"expiry-retry-store"));due(id);var before=businessRows();
        SqlProbe.before.set(statement -> {
            if(statement.endsWith("ExchangeLifecycleMapper.tryLock") || statement.endsWith("ExchangeExpiryMapper.defer"))
                throw new IllegalStateException("fictional-sensitive-payload-should-never-be-logged");
        });
        try {new ExchangeExpiryScheduler(expiryScanner).poll();}
        finally {SqlProbe.before.remove();}
        assertFalse(output.getAll().contains("fictional-sensitive-payload-should-never-be-logged"));
        assertEquals(before,businessRows());assertEquals(1,expiryScanner.scanBatch().expired());
    }

    @Test void mysqlTwoScannersSkipLockedExchangeAndCommitOnlyOneExpiry() throws Exception {
        mysqlOnly();long id=creation.create(a.id(),ring(2,"expiry-workers"));due(id);
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try {
            var first=pool.submit(()-> {
                SqlProbe.before.set(statement -> {if(statement.endsWith("ExchangeLifecycleMapper.transition")){locked.countDown();await(release);}});
                try{return expiryScanner.scanBatch();}finally{SqlProbe.before.remove();}
            });
            await(locked);
            var second=expiryScanner.scanBatch();assertEquals(0,second.selected());assertEquals(0,second.expired());
            release.countDown();assertEquals(1,first.get(10,java.util.concurrent.TimeUnit.SECONDS).expired());
            assertEquals(1,eventCount(id));assertEquals(0,expiryScanner.scanBatch().selected());
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test void mysqlExpiryBatchSkipsOldestBusyExchangeAndProcessesTheNextDueRow() throws Exception {
        mysqlOnly();
        long busy=creation.create(a.id(),ring(2,"expiry-busy-first"));
        long available=creation.create(a.id(),ring(2,"expiry-available-next"));
        var now=exchangeClock.now();
        jdbc.update("UPDATE cl_exchange SET expires_at=? WHERE id=?",now.minusSeconds(2),busy);
        jdbc.update("UPDATE cl_exchange SET expires_at=? WHERE id=?",now.minusSeconds(1),available);
        var single=new ExchangeExpiryScanner(expiryQueue,lifecycle,exchangeClock,exchangeTransactions,1);
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        try {
            var blocker=pool.submit(()->tx.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM cl_exchange WHERE id=? FOR UPDATE",busy);
                locked.countDown();await(release);
            }));
            await(locked);
            var first=single.scanBatch();
            assertEquals(1,first.selected());assertEquals(1,first.expired());
            assertEquals("EXPIRED",state(available));assertEquals("AWAITING_CONFIRMATION",state(busy));
            assertEquals(1,eventCount(available));assertEquals(0,eventCount(busy));
            release.countDown();blocker.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1,single.scanBatch().expired());assertEquals(1,eventCount(busy));
            assertEquals(0,single.scanBatch().selected());
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test void mysqlScannerSerializesExpiryAgainstConfirmAndCancel() throws Exception {
        mysqlOnly();
        for(boolean cancel:List.of(false,true)) {
            long id=creation.create(a.id(),ring(2,"expiry-action-"+cancel));due(id);
            var result=race(()->(long)expiryScanner.scanBatch().expired(),()->cancel?lifecycle.cancel(b.id(),id,0,"原因"):lifecycle.confirm(a.id(),id,0),
                "ExchangeLifecycleMapper.transition","ExchangeLifecycleMapper.lock");
            assertEquals(List.of(1L,-409L),result);assertEquals(1,eventCount(id));assertEquals("EXPIRED",state(id));
        }
    }

    @Test void mysqlFreshJvmAutomaticallyRecoversDueWorkAfterProcessStop() throws Exception {
        mysqlOnly();long id=creation.create(a.id(),ring(3,"expiry-restart"));
        var retryCommand=ring(2,"expiry-restart-retry");long retryId=creation.create(a.id(),retryCommand);due(retryId);
        long retryItem=retryCommand.flows().get(0).itemId();
        jdbc.update("UPDATE cl_item SET version=version+1 WHERE id=?",retryItem);
        assertEquals(1,expiryScanner.scanBatch().deferred());assertEquals(1,retryCount(retryId));
        jdbc.update("UPDATE cl_item SET version=version-1 WHERE id=?",retryItem); // Repair only the deliberate isolated corruption.

        var log=java.nio.file.Files.createTempFile("campus-expiry-recovery-",".log");
        Process first=null,second=null;
        try {
            first=startExpiryApplication(false,log);waitStarted(first,log);
            assertEquals("AWAITING_CONFIRMATION",state(id));long firstPid=first.pid();
            first.destroyForcibly();assertTrue(first.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));first=null;
            jdbc.update("UPDATE cl_exchange SET expiry_retry_at=? WHERE id=?",exchangeClock.now().minusSeconds(1),retryId);
            due(id); // Deadline passes while the application is stopped; no in-memory task is retained.
            java.nio.file.Files.writeString(log,"");
            second=startExpiryApplication(true,log);waitStarted(second,log);assertNotEquals(firstPid,second.pid());
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while((!state(id).equals("EXPIRED") || !state(retryId).equals("EXPIRED")) && System.nanoTime()<until) Thread.sleep(100);
            assertEquals("EXPIRED",state(id));assertEquals(1,eventCount(id));
            assertEquals("EXPIRED",state(retryId));assertEquals(1,eventCount(retryId));assertEquals(1,retryCount(retryId));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold WHERE exchange_id=?",Integer.class,id));
            Thread.sleep(500);assertEquals(1,eventCount(id));
            System.out.println("A-04 recovery: two distinct JVMs; stopped process, persisted deadline, automatic EXPIRED and one event verified.");
        } finally {
            for(Process process:new Process[]{first,second}) if(process!=null) {process.destroyForcibly();process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS);}
            java.nio.file.Files.deleteIfExists(log);
        }
    }
    private Process startExpiryApplication(boolean enabled,java.nio.file.Path log) throws Exception {
        String javaExecutable=java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString();
        var builder=new ProcessBuilder(javaExecutable,"-Duser.timezone=Pacific/Honolulu","-cp",
            System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),"edu.campusloop.CampusLoopApplication",
            "--spring.profiles.active=mysql-test","--server.address=127.0.0.1","--server.port=0",
            "--campus.exchange-expiry.enabled="+enabled,"--campus.exchange-expiry.delay-ms=200",
            "--campus.exchange-expiry.initial-delay-ms=100","--campus.bootstrap-enabled=false","--campus.demo-enabled=false");
        builder.environment().put("JWT_SECRET",UUID.randomUUID().toString()+UUID.randomUUID());
        builder.redirectErrorStream(true).redirectOutput(log.toFile());return builder.start();
    }
    private void waitStarted(Process process,java.nio.file.Path log) throws Exception {
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while(process.isAlive() && System.nanoTime()<until) {
            if(java.nio.file.Files.readString(log).contains("Started CampusLoopApplication")) return;
            Thread.sleep(100);
        }
        fail("Isolated recovery JVM did not start; raw application logs are intentionally not exposed");
    }
    private void due(long id) {jdbc.update("UPDATE cl_exchange SET expires_at=? WHERE id=?",exchangeClock.now().minusSeconds(1),id);}
    private String state(long id) {return jdbc.queryForObject("SELECT status FROM cl_exchange WHERE id=?",String.class,id);}
    private int retryCount(long id) {return jdbc.queryForObject("SELECT expiry_retry_count FROM cl_exchange WHERE id=?",Integer.class,id);}
    private Map<String,List<Map<String,Object>>> businessWithoutRetry() {
        var rows=businessRows();
        for(var row:rows.get("cl_exchange")) for(String field:List.of("expiry_retry_at","expiry_retry_count","expiry_failure_code")) row.remove(field);
        return rows;
    }

    private int eventCount(long id) { return jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange_event WHERE exchange_id=?",Integer.class,id); }

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
        return race(first,second,"ExchangeCreationMapper.insert","UserMapper.selectByIdForUpdate");
    }
    private List<Long> race(java.util.concurrent.Callable<Long> first,java.util.concurrent.Callable<Long> second,String pauseStatement,String waitStatement) throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var waiting=new java.util.concurrent.CountDownLatch(1);
        try {
            var one=pool.submit(()-> {
                SqlProbe.before.set(id -> {if(id.endsWith(pauseStatement)){locked.countDown();await(release);}});
                try{return result(first);}finally{SqlProbe.before.remove();}
            });
            await(locked);
            var two=pool.submit(()-> {
                SqlProbe.before.set(id -> {if(id.endsWith(waitStatement))waiting.countDown();});
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
        if(administrator) jdbc.update("UPDATE cl_user SET role='ADMIN',admin_permissions='ALL' WHERE id=?",id);
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
    private static final edu.campusloop.exchange.ExchangeLifecycleRules.HandoffKind GIVEN=edu.campusloop.exchange.ExchangeLifecycleRules.HandoffKind.HANDED_OFF;
    private static final edu.campusloop.exchange.ExchangeLifecycleRules.HandoffKind RECEIVED=edu.campusloop.exchange.ExchangeLifecycleRules.HandoffKind.RECEIVED;
    private long ready(int length,String key) {
        long id=creation.create(a.id(),ring(length,key));
        for(int i=0;i<length;i++) lifecycle.confirm(List.of(a,b,c).get(i).id(),id,i);
        return id;
    }
    private int almostComplete(long id,int length) {
        int version=length;
        for(int i=0;i<length;i++) {
            var actor=List.of(a,b,c).get(i);
            lifecycle.handoff(actor.id(),id,version++,GIVEN,"已交出");
            if(i<length-1) lifecycle.handoff(actor.id(),id,version++,RECEIVED,"已收到");
        }
        return version;
    }
    @Test void handoffTwoAndThreePartyFlowsTransferExactlyOnceAndCloseOnlySelectedDemands() throws Exception {
        for(int length:List.of(2,3)) {
            long unrelated=demand(a,2,item(a,3));
            long id=ready(length,"handoff-complete-"+length);
            var itemsBefore=jdbc.queryForList("SELECT i.* FROM cl_item i JOIN cl_exchange_participant p ON p.offered_item_id=i.id WHERE p.exchange_id=? ORDER BY i.id",id);
            var refs=jdbc.queryForList("SELECT demand_id FROM cl_exchange_demand WHERE exchange_id=? ORDER BY demand_id",Long.class,id);
            int version=almostComplete(id,length);
            assertEquals(itemsBefore,jdbc.queryForList("SELECT i.* FROM cl_item i JOIN cl_exchange_participant p ON p.offered_item_id=i.id WHERE p.exchange_id=? ORDER BY i.id",id));
            assertEquals("READY",jdbc.queryForObject("SELECT status FROM cl_exchange WHERE id=?",String.class,id));
            assertEquals(length,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold WHERE exchange_id=?",Integer.class,id));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_history WHERE exchange_id=?",Integer.class,id));
            var actor=List.of(a,b,c).get(length-1);
            var result=call("POST","/api/exchanges/"+id+"/handoff",actor.token(),Map.of("version",version,"kind","RECEIVED","acknowledged",true,"note","已收到"),200);
            assertEquals("COMPLETED",result.path("status").asText());assertTrue(result.path("allowedActions").isEmpty());
            for(var flow:result.path("flows")) {
                long item=flow.path("itemId").asLong();
                assertEquals(flow.path("toUserId").asLong(),jdbc.queryForObject("SELECT owner_id FROM cl_item WHERE id=?",Long.class,item));
                assertEquals("EXCHANGED",jdbc.queryForObject("SELECT status FROM cl_item WHERE id=?",String.class,item));
                assertEquals(2,jdbc.queryForObject("SELECT version FROM cl_item WHERE id=?",Integer.class,item));
                var event=jdbc.queryForMap("SELECT * FROM cl_item_history WHERE exchange_id=? AND item_id=?",id,item);
                assertEquals("BOTH_CONFIRMED",event.get("evidence_level"));assertNull(event.get("verified_by_user_id"));assertNull(event.get("verified_at"));
                assertEquals(flow.path("fromUserId").asLong(),((Number)event.get("source_user_id")).longValue());
                assertEquals(flow.path("toUserId").asLong(),((Number)event.get("counterparty_user_id")).longValue());
            }
            for(long demand:refs) {
                assertEquals("INACTIVE",jdbc.queryForObject("SELECT status FROM cl_demand WHERE id=?",String.class,demand));
                assertEquals(1,jdbc.queryForObject("SELECT version FROM cl_demand WHERE id=?",Integer.class,demand));
            }
            assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM cl_demand WHERE id=?",String.class,unrelated));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold WHERE exchange_id=?",Integer.class,id));
            assertEquals(length*3,eventCount(id));var rows=businessRows();
            lifecycle.handoff(actor.id(),id,version,RECEIVED," 已收到 ");
            lifecycle.handoff(a.id(),id,length,GIVEN,"已交出");
            call("GET","/api/exchanges/"+id,a.token(),null,200);
            assertEquals(rows,businessRows());
        }
    }
    @Test void handoffRejectsUnauthorizedForgedMalformedStaleAndChangedReplayWithoutWrites() throws Exception {
        long id=ready(2,"handoff-auth");var rows=businessRows();
        var body=Map.of("version",2,"kind","RECEIVED","acknowledged",true);
        call("POST","/api/exchanges/"+id+"/handoff",null,body,401);
        for(var other:List.of(outsider,admin)) call("POST","/api/exchanges/"+id+"/handoff",other.token(),body,404);
        for(var bad:List.of(Map.of("version",2,"kind","RECEIVED","acknowledged",false),Map.of("version",2,"kind","OTHER","acknowledged",true),Map.of("version",2,"kind","RECEIVED","acknowledged",true,"ownerId",b.id()),Map.of("version",2.5,"kind","RECEIVED","acknowledged",true),Map.of("version",2,"kind","RECEIVED","acknowledged",true,"note","x".repeat(1001))))
            call("POST","/api/exchanges/"+id+"/handoff",a.token(),bad,400);
        rejected(409,()->lifecycle.handoff(a.id(),id,1,GIVEN,""));assertEquals(rows,businessRows());
        lifecycle.handoff(a.id(),id,2,GIVEN,"");rows=businessRows();
        rejected(409,()->lifecycle.handoff(a.id(),id,3,GIVEN,"改写"));
        rejected(409,()->lifecycle.handoff(a.id(),id,4,GIVEN,""));
        rejected(409,()->lifecycle.handoff(b.id(),id,2,RECEIVED,""));assertEquals(rows,businessRows());
        long waiting=creation.create(a.id(),ring(2,"handoff-waiting"));
        rejected(409,()->lifecycle.handoff(a.id(),waiting,0,GIVEN,""));
    }
    @Test void startedHandoffContinuesAfterDeadlineButDisputeStopsItWithoutRelease() throws Exception {
        long id=ready(2,"handoff-dispute");
        rejected(409,()->lifecycle.dispute(a.id(),id,2,"异常"));
        lifecycle.handoff(a.id(),id,2,GIVEN,"交出");due(id);
        assertFalse(lifecycle.expire(id));assertFalse(lifecycle.expireForScan(id));
        rejected(409,()->lifecycle.cancel(a.id(),id,3,"取消"));
        lifecycle.handoff(b.id(),id,3,RECEIVED,"收到");
        var resources=jdbc.queryForList("SELECT * FROM cl_item_hold WHERE exchange_id=?",id);
        var dispute=call("POST","/api/exchanges/"+id+"/dispute",a.token(),Map.of("version",4,"reason","  物品异常  "),200);
        assertEquals("DISPUTED",dispute.path("status").asText());assertEquals(a.id(),dispute.path("disputedBy").asLong());
        assertEquals("物品异常",dispute.path("disputeReason").asText());assertTrue(dispute.path("disputedAt").asText().endsWith("Z"));assertTrue(dispute.path("allowedActions").isEmpty());
        var rows=businessRows();lifecycle.dispute(a.id(),id,4,"物品异常");lifecycle.handoff(a.id(),id,2,GIVEN,"交出");
        rejected(404,()->lifecycle.dispute(outsider.id(),id,5,"物品异常"));
        rejected(409,()->lifecycle.dispute(b.id(),id,5,"物品异常"));
        rejected(409,()->lifecycle.handoff(b.id(),id,5,GIVEN,""));assertFalse(lifecycle.expire(id));
        assertEquals(rows,businessRows());assertEquals(resources,jdbc.queryForList("SELECT * FROM cl_item_hold WHERE exchange_id=?",id));
    }
    @Test void firstHandoffAtDeadlineRejectedAndAllStartedAcknowledgementsMayCompleteAfterIt() {
        long rejectedId=ready(2,"handoff-deadline");due(rejectedId);var rows=businessRows();
        rejected(409,()->lifecycle.handoff(a.id(),rejectedId,2,GIVEN,""));assertEquals(rows,businessRows());
        long id=ready(2,"handoff-continue");int version=almostComplete(id,2);due(id);
        lifecycle.handoff(b.id(),id,version,RECEIVED,"已收到");assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM cl_exchange WHERE id=?",String.class,id));
    }
    @Test void changedDemandOrForeignHoldPreventsCompletionAndPreservesAllRows() {
        for(String change:List.of("version","status","reference","hold")) {
            long id=ready(2,"handoff-guard-"+change);int version=almostComplete(id,2);
            long demand=jdbc.queryForObject("SELECT MIN(demand_id) FROM cl_exchange_demand WHERE exchange_id=?",Long.class,id);
            if(change.equals("version")) jdbc.update("UPDATE cl_demand SET version=version+1 WHERE id=?",demand);
            if(change.equals("status")) jdbc.update("UPDATE cl_demand SET status='INACTIVE' WHERE id=?",demand);
            if(change.equals("reference")) jdbc.update("UPDATE cl_exchange_demand SET demand_version=demand_version+1 WHERE exchange_id=? AND demand_id=?",id,demand);
            if(change.equals("hold")) {
                long other=creation.create(a.id(),ring(2,"handoff-other-hold"));
                jdbc.update("UPDATE cl_item_hold SET exchange_id=? WHERE item_id=(SELECT MIN(offered_item_id) FROM cl_exchange_participant WHERE exchange_id=?)",other,id);
            }
            var rows=businessRows();rejected(409,()->lifecycle.handoff(b.id(),id,version,RECEIVED,"已收到"));assertEquals(rows,businessRows());
        }
    }
    @Test void completionConstraintFailureAtEveryWritePhaseRollsBackAllSixBusinessEffects() {
        for(String stage:List.of("receive","transition","event","fulfill","transfer","history","release")) {
            long id=ready(3,"handoff-failure-"+stage);int version=almostComplete(id,3);var rows=businessRows();
            var once=new java.util.concurrent.atomic.AtomicBoolean();
            SqlProbe.after.set(statement -> {
                if(statement.endsWith("ExchangeLifecycleMapper."+stage) && once.compareAndSet(false,true))
                    jdbc.update("INSERT INTO cl_exchange(initiator_id,status,idempotency_key,expires_at) SELECT initiator_id,status,idempotency_key,expires_at FROM cl_exchange WHERE id=?",id);
            });
            try {rejected(409,()->lifecycle.handoff(c.id(),id,version,RECEIVED,"已收到"));} finally {SqlProbe.after.remove();}
            assertTrue(once.get());assertEquals(rows,businessRows());
            lifecycle.handoff(c.id(),id,version,RECEIVED,"已收到");assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_history WHERE exchange_id=?",Integer.class,id));
        }
    }
    @Test void mysqlConcurrentLastAcknowledgementsAndExactReplayTransferOnce() throws Exception {
        mysqlOnly();long id=ready(2,"handoff-last-race");
        lifecycle.handoff(a.id(),id,2,GIVEN,"");lifecycle.handoff(b.id(),id,3,GIVEN,"");
        var result=race(()->lifecycle.handoff(a.id(),id,4,RECEIVED,""),()->lifecycle.handoff(b.id(),id,4,RECEIVED,""),"ExchangeLifecycleMapper.transition","ExchangeLifecycleMapper.lock");
        assertEquals(List.of(id,-409L),result);assertEquals("READY",jdbc.queryForObject("SELECT status FROM cl_exchange WHERE id=?",String.class,id));
        result=race(()->lifecycle.handoff(b.id(),id,5,RECEIVED,""),()->lifecycle.handoff(b.id(),id,5,RECEIVED,""),"ExchangeLifecycleMapper.transition","ExchangeLifecycleMapper.lock");
        assertEquals(List.of(id,id),result);assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_history WHERE exchange_id=?",Integer.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold WHERE exchange_id=?",Integer.class,id));
    }
    @Test void mysqlCancelExpiryAndDisputeRaceThroughSharedExchangeLock() throws Exception {
        mysqlOnly();
        long first=ready(2,"handoff-cancel-race");
        assertEquals(List.of(first,-409L),race(()->lifecycle.handoff(a.id(),first,2,GIVEN,""),()->lifecycle.cancel(b.id(),first,2,"取消"),"ExchangeLifecycleMapper.transition","ExchangeLifecycleMapper.lock"));
        assertEquals(List.of(first,0L),race(()->lifecycle.handoff(b.id(),first,3,RECEIVED,""),()->lifecycle.expire(first)?1L:0L,"ExchangeLifecycleMapper.transition","ExchangeLifecycleMapper.lock"));
        long second=ready(2,"cancel-handoff-race");
        assertEquals(List.of(second,-409L),race(()->lifecycle.cancel(b.id(),second,2,"取消"),()->lifecycle.handoff(a.id(),second,2,GIVEN,""),"ExchangeLifecycleMapper.transition","ExchangeLifecycleMapper.lock"));
        long third=ready(2,"expiry-handoff-race");due(third);
        assertEquals(List.of(third,-409L),race(()->{lifecycle.expire(third);return third;},()->lifecycle.handoff(a.id(),third,2,GIVEN,""),"ExchangeLifecycleMapper.transition","ExchangeLifecycleMapper.lock"));
        long fourth=ready(2,"dispute-handoff-race");int version=almostComplete(fourth,2);
        assertEquals(List.of(fourth,-409L),race(()->lifecycle.dispute(a.id(),fourth,version,"异常"),()->lifecycle.handoff(b.id(),fourth,version,RECEIVED,"已收到"),"ExchangeLifecycleMapper.transition","ExchangeLifecycleMapper.lock"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold WHERE exchange_id=?",Integer.class,fourth));
    }

    @Autowired edu.campusloop.web.history.service.HistoryService histories;
    private Map<String,Object> statement(String type,String text,Long exchange,Long corrects,List<String> evidence) {
        Map<String,Object> body=new LinkedHashMap<>();body.put("eventType",type);body.put("statement",text);
        body.put("occurredAt",null);body.put("timeUnknown",true);body.put("relatedExchangeId",exchange);body.put("correctsEventId",corrects);body.put("evidenceUploadIds",evidence);return body;
    }
    private String evidenceUpload(Account actor,boolean privateEvidence) throws Exception {
        var pixels=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(pixels,"png",bytes);
        var file=new org.springframework.mock.web.MockMultipartFile("file","fictional.png","image/png",bytes.toByteArray());
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(privateEvidence?"/api/uploads/evidence":"/api/uploads")
            .file(file).header("Authorization","Bearer "+actor.token())).andReturn().getResponse();
        assertEquals(200,response.getStatus());var data=json.readTree(response.getContentAsString()).path("data");
        return privateEvidence?data.path("uploadId").asText():data.path("url").asText().substring(9,data.path("url").asText().length()-4);
    }
    private void evidenceRead(String id,Account actor,int status) throws Exception {
        var req=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/history-evidence/"+id);
        if(actor!=null) req.header("Authorization","Bearer "+actor.token());
        var response=mvc.perform(req).andReturn().getResponse();assertEquals(status,response.getStatus());
        if(status==200) {assertEquals("image/png",response.getContentType());assertTrue(response.getHeader("Cache-Control").contains("no-store"));assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));assertTrue(response.getContentAsByteArray().length>0);}
    }
    @Test void selfReportedUnknownAndKnownTimePersistAndPublicProjectionHidesPrivateEvidence() throws Exception {
        long item=item(a,1);String evidence=evidenceUpload(a,true);evidenceRead(evidence,a,200);evidenceRead(evidence,admin,404);evidenceRead(evidence,null,401);
        var before=exchangeClock.now();var created=call("POST","/api/items/"+item+"/history",a.token(),statement("REPAIR"," 更换虚构零件 ",null,null,List.of(evidence)),200);
        long event=created.path("id").asLong();assertTrue(created.path("timeUnknown").asBoolean());assertTrue(created.path("occurredAt").isNull());
        assertEquals("SELF_REPORTED",created.path("evidenceLevel").asText());assertEquals(a.id(),created.path("authorId").asLong());
        var recorded=java.time.Instant.parse(created.path("recordedAt").asText());assertFalse(recorded.isBefore(before.toInstant(java.time.ZoneOffset.UTC)));assertFalse(recorded.isAfter(exchangeClock.now().toInstant(java.time.ZoneOffset.UTC)));
        assertEquals(created,call("GET","/api/items/"+item+"/history/"+event,a.token(),null,200));
        var publicView=call("GET","/api/items/"+item+"/history/"+event,null,null,200);
        assertTrue(publicView.path("evidence").isNull());assertTrue(publicView.path("relatedExchangeId").isNull());assertTrue(publicView.path("authorId").isNull());assertFalse(publicView.toString().contains(evidence));
        evidenceRead(evidence,b,404);evidenceRead(evidence,admin,200);
        assertEquals(404,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/uploads/"+evidence+".png")).andReturn().getResponse().getStatus());
        var known=statement("TRANSFER","平台外流转自述，不变更owner",null,null,List.of());known.put("timeUnknown",false);known.put("occurredAt",before.minusDays(1).toInstant(java.time.ZoneOffset.UTC).toString());
        var second=call("POST","/api/items/"+item+"/history",a.token(),known,200);
        assertFalse(second.path("timeUnknown").asBoolean());assertEquals(known.get("occurredAt"),second.path("occurredAt").asText());assertEquals(a.id(),jdbc.queryForObject("SELECT owner_id FROM cl_item WHERE id=?",Long.class,item));
        var rows=businessRows();var page=call("GET","/api/items/"+item+"/history?page=1&size=1",null,null,200);
        assertEquals(2,page.path("total").asInt());assertEquals(second.path("id"),page.path("records").get(0).path("id"));
        assertEquals(event,call("GET","/api/items/"+item+"/history?page=2&size=1",null,null,200).path("records").get(0).path("id").asLong());assertEquals(rows,businessRows());
    }
    @Test void selfReportRejectsForgedCredibilityAuthorsInvalidTimesAndForeignOrPublicEvidence() throws Exception {
        long item=item(a,1);String foreign=evidenceUpload(b,true),publicId=evidenceUpload(a,false);var rows=businessRows();
        for(String field:List.of("ownerId","authorId","sourceUserId","evidenceLevel","sourceLevel","recordedAt","verifiedByUserId")) {
            var body=statement("REPAIR","自述",null,null,List.of());body.put(field,"ADMIN_VERIFIED");call("POST","/api/items/"+item+"/history",a.token(),body,400);
        }
        for(String id:List.of(foreign,publicId)) call("POST","/api/items/"+item+"/history",a.token(),statement("REPAIR","自述",null,null,List.of(id)),400);
        call("POST","/api/items/"+item+"/history",a.token(),statement("REPAIR","自述",null,null,List.of(foreign,foreign)),400);
        call("POST","/api/items/"+item+"/history",b.token(),statement("REPAIR","越权",null,null,List.of()),403);
        call("POST","/api/items/"+item+"/history",admin.token(),statement("REPAIR","管理员不能代自述",null,null,List.of()),403);
        call("POST","/api/items/"+item+"/history",null,statement("REPAIR","无登录",null,null,List.of()),401);
        call("POST","/api/items/"+item+"/history",a.token(),statement("EXCHANGED","伪造真实交换",null,null,List.of()),400);
        var future=statement("REPAIR","未来",null,null,List.of());future.put("timeUnknown",false);future.put("occurredAt",exchangeClock.now().plusSeconds(30).toInstant(java.time.ZoneOffset.UTC).toString());call("POST","/api/items/"+item+"/history",a.token(),future,400);
        future.put("timeUnknown",true);call("POST","/api/items/"+item+"/history",a.token(),future,400);
        for(String query:List.of("page=0","size=101","page=1&page=2","ownerId=1")) call("GET","/api/items/"+item+"/history?"+query,null,null,400);
        call("GET","/api/items/"+item+"/history","invalid-token",null,401);
        assertEquals(rows,businessRows());
        var bad=new org.springframework.mock.web.MockMultipartFile("file","fake.svg","image/svg+xml","not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(400,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/uploads/evidence").file(bad).header("Authorization","Bearer "+a.token())).andReturn().getResponse().getStatus());
        assertEquals(rows,businessRows());
    }
    @Test void correctionsAppendKeepOriginalAndOnlyOriginalAuthorMayExtendSingleChain() throws Exception {
        long item=item(a,1);String evidence=evidenceUpload(a,true);
        long original=call("POST","/api/items/"+item+"/history",a.token(),statement("REPAIR","原始声明",null,null,List.of(evidence)),200).path("id").asLong();
        var old=jdbc.queryForMap("SELECT * FROM cl_item_history WHERE id=?",original);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO cl_item_history(item_id,event_type,description,source_user_id,corrects_event_id) VALUES (?,'REPAIR','非法他人引用',?,?)",item,b.id(),original));
        long correction=call("POST","/api/items/"+item+"/history",a.token(),statement("REPAIR","更正说明，保留原文",null,original,List.of(evidence)),200).path("id").asLong();
        assertEquals(old,jdbc.queryForMap("SELECT * FROM cl_item_history WHERE id=?",original));
        var originalView=call("GET","/api/items/"+item+"/history/"+original,a.token(),null,200);assertEquals("原始声明",originalView.path("statement").asText());assertEquals(correction,originalView.path("correctedByEventId").asLong());assertFalse(originalView.path("canCorrect").asBoolean());
        var rows=businessRows();call("POST","/api/items/"+item+"/history",a.token(),statement("REPAIR","分叉",null,original,List.of()),409);
        call("POST","/api/items/"+item+"/history",b.token(),statement("REPAIR","他人更正",null,correction,List.of()),403);
        call("POST","/api/items/"+item+"/history",admin.token(),statement("REPAIR","管理员不能覆盖",null,correction,List.of()),403);
        for(String method:List.of("PUT","PATCH","DELETE")) {
            var response=mvc.perform(request(HttpMethod.valueOf(method),"/api/items/"+item+"/history/"+original).header("Authorization","Bearer "+a.token()).contentType("application/json").content("{}")).andReturn().getResponse();assertEquals(405,response.getStatus());
        }
        assertEquals(rows,businessRows());
        long next=call("POST","/api/items/"+item+"/history",a.token(),statement("REPAIR","再次修正",null,correction,List.of()),200).path("id").asLong();assertTrue(next>correction);
    }
    @Test void realB04TransfersProveFormerOwnershipAndEvidenceIsLimitedToExactItemPair() throws Exception {
        long exchange=ready(3,"history-transfer");long item=jdbc.queryForObject("SELECT offered_item_id FROM cl_exchange_participant WHERE exchange_id=? AND user_id=?",Long.class,exchange,a.id());
        String oldEvidence=evidenceUpload(a,true);long oldEvent=call("POST","/api/items/"+item+"/history",a.token(),statement("REPAIR","交出前自述",null,null,List.of(oldEvidence)),200).path("id").asLong();
        call("POST","/api/items/"+item+"/history",a.token(),statement("REPAIR","未完成交换不能证明",exchange,null,List.of()),409);
        int version=almostComplete(exchange,3);lifecycle.handoff(c.id(),exchange,version,RECEIVED,"已收到");
        var actual=jdbc.queryForMap("SELECT * FROM cl_item_history WHERE item_id=? AND event_type='EXCHANGED'",item);long fact=((Number)actual.get("id")).longValue();
        assertEquals("BOTH_CONFIRMED",call("GET","/api/items/"+item+"/history/"+fact,null,null,200).path("evidenceLevel").asText());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO cl_item_history(item_id,event_type,description,source_user_id,corrects_event_id) VALUES (?,'REPAIR','禁止改写真实来源',?,?)",item,a.id(),fact));
        String evidence=evidenceUpload(a,true);long former=call("POST","/api/items/"+item+"/history",a.token(),statement("REPAIR","曾经持有期间的自述",exchange,null,List.of(evidence)),200).path("id").asLong();
        evidenceRead(evidence,a,200);evidenceRead(evidence,b,200);evidenceRead(evidence,c,404);evidenceRead(evidence,admin,200);
        evidenceRead(oldEvidence,b,404);assertTrue(call("GET","/api/items/"+item+"/history/"+oldEvent,b.token(),null,200).path("evidence").isNull());
        var publicView=call("GET","/api/items/"+item+"/history/"+former,c.token(),null,200);assertTrue(publicView.path("relatedExchangeId").isNull());assertTrue(publicView.path("evidence").isNull());
        call("POST","/api/items/"+item+"/history",c.token(),statement("REPAIR","第三人",exchange,null,List.of()),403);
        call("POST","/api/items/"+item+"/history",a.token(),statement("REPAIR","无持有证明",null,null,List.of()),403);
        var early=statement("REPAIR","接收前不属本人",null,null,List.of());early.put("timeUnknown",false);early.put("occurredAt",jdbc.queryForObject("SELECT occurred_at FROM cl_item_history WHERE id=?",java.time.LocalDateTime.class,fact).minusSeconds(1).toInstant(java.time.ZoneOffset.UTC).toString());call("POST","/api/items/"+item+"/history",b.token(),early,400);
        for(var actor:List.of(a,b,admin)) call("POST","/api/items/"+item+"/history",actor.token(),statement("REPAIR","不能改B04事实",exchange,fact,List.of()),403);
        assertEquals(actual,jdbc.queryForMap("SELECT * FROM cl_item_history WHERE id=?",fact));
        call("POST","/api/items/"+item+"/history",a.token(),statement("REPAIR","转手后仍可修正原声明",null,oldEvent,List.of(oldEvidence)),200);
        long privateOwnerEvent=call("POST","/api/items/"+item+"/history",b.token(),statement("REPAIR","新所有者独立自述",null,null,List.of()),200).path("id").asLong();
        jdbc.update("UPDATE cl_item SET status='HIDDEN' WHERE id=?",item);
        call("GET","/api/items/"+item+"/history/"+privateOwnerEvent,a.token(),null,404);
        var formerPage=call("GET","/api/items/"+item+"/history",a.token(),null,200);
        assertTrue(formerPage.path("records").findValues("id").stream().noneMatch(value->value.asLong()==privateOwnerEvent));
        assertEquals(formerPage.path("total").asInt()+1,call("GET","/api/items/"+item+"/history",admin.token(),null,200).path("total").asInt());
        call("GET","/api/items/"+item+"/history",null,null,404);call("GET","/api/items/"+item+"/history",c.token(),null,404);
        assertTrue(call("GET","/api/items/"+item+"/history",a.token(),null,200).path("total").asInt()>0);
        assertTrue(call("GET","/api/items/"+item+"/history",admin.token(),null,200).path("total").asInt()>0);
    }
    @Test void historyEvidenceConstraintFailuresRollBackAppendAndReferencesTogether() throws Exception {
        long exchange=ready(2,"history-rollback");long item=item(a,1);String evidence=evidenceUpload(a,true);
        for(String phase:List.of("append","evidence")) {
            var rows=businessRows();var once=new java.util.concurrent.atomic.AtomicBoolean();
            SqlProbe.after.set(statement->{if(statement.endsWith("HistoryMapper."+phase) && once.compareAndSet(false,true)) jdbc.update("INSERT INTO cl_exchange(initiator_id,status,idempotency_key,expires_at) SELECT initiator_id,status,idempotency_key,expires_at FROM cl_exchange WHERE id=?",exchange);});
            try {rejected(409,()->histories.create(a.id(),item,json.convertValue(statement("REPAIR","完整回滚",null,null,List.of(evidence)),edu.campusloop.web.history.dto.HistoryRequest.class)));} finally {SqlProbe.after.remove();}
            assertTrue(once.get());assertEquals(rows,businessRows());
        }
        long event=call("POST","/api/items/"+item+"/history",a.token(),statement("REPAIR","恢复后保存",null,null,List.of(evidence)),200).path("id").asLong();
        var rows=businessRows();assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("DELETE FROM cl_upload WHERE id=?",evidence));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO cl_history_evidence(history_id,upload_id) VALUES (?,?)",event,evidence));assertEquals(rows,businessRows());
    }
    @Test void mysqlConcurrentCorrectionsSerializeAndCannotForkOrOverwriteHistory() throws Exception {
        mysqlOnly();long item=item(a,1);
        long original=call("POST","/api/items/"+item+"/history",a.token(),statement("REPAIR","原文",null,null,List.of()),200).path("id").asLong();
        var cmd=json.convertValue(statement("REPAIR","修正",null,original,List.of()),edu.campusloop.web.history.dto.HistoryRequest.class);
        var result=race(()->histories.create(a.id(),item,cmd),()->histories.create(a.id(),item,cmd),"HistoryMapper.append","UserMapper.selectByIdForUpdate");
        assertTrue(result.get(0)>0);assertEquals(-409L,result.get(1));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_history WHERE corrects_event_id=?",Integer.class,original));
        assertEquals("原文",jdbc.queryForObject("SELECT description FROM cl_item_history WHERE id=?",String.class,original));
        var rows=businessRows();assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO cl_item_history(item_id,event_type,description,source_user_id,corrects_event_id) VALUES (?,'REPAIR','非法分叉',?,?)",item,a.id(),original));assertEquals(rows,businessRows());
    }
    @Test void mysqlFinalTransferAndSelfReportUseCompatibleLocksAndRecheckOwnership() throws Exception {
        mysqlOnly();long exchange=ready(2,"history-owner-race");int version=almostComplete(exchange,2);
        long item=jdbc.queryForObject("SELECT offered_item_id FROM cl_exchange_participant WHERE exchange_id=? AND user_id=?",Long.class,exchange,a.id());
        var cmd=json.convertValue(statement("REPAIR","并发自述",null,null,List.of()),edu.campusloop.web.history.dto.HistoryRequest.class);
        assertEquals(List.of(exchange,-403L),race(()->lifecycle.handoff(b.id(),exchange,version,RECEIVED,"已收到"),()->histories.create(a.id(),item,cmd),"ExchangeLifecycleMapper.transition","UserMapper.selectByIdForUpdate"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_history WHERE item_id=? AND evidence_level='SELF_REPORTED'",Integer.class,item));
    }

    @Test void mysqlLinkedHistoryTakesExchangeAndUsersBeforeForeignKeysWithoutDeadlockRetries() throws Exception {
        mysqlOnly();long exchange=ready(3,"history-expiry-lock");int version=almostComplete(exchange,3);
        lifecycle.handoff(c.id(),exchange,version,RECEIVED,"已收到");
        long item=jdbc.queryForObject("SELECT offered_item_id FROM cl_exchange_participant WHERE exchange_id=? AND user_id=?",Long.class,exchange,a.id());
        var cmd=json.convertValue(statement("REPAIR","关联交换的旧经历",exchange,null,List.of()),edu.campusloop.web.history.dto.HistoryRequest.class);
        var appendAttempts=new java.util.concurrent.atomic.AtomicInteger();var expiryAttempts=new java.util.concurrent.atomic.AtomicInteger();
        var result=race(()-> {
            var probe=SqlProbe.before.get();SqlProbe.before.set(id->{if(id.endsWith("HistoryMapper.append")) appendAttempts.incrementAndGet();probe.accept(id);});
            return histories.create(a.id(),item,cmd);
        },()-> {
            var probe=SqlProbe.before.get();SqlProbe.before.set(id->{if(id.endsWith("ExchangeLifecycleMapper.lock")) expiryAttempts.incrementAndGet();probe.accept(id);});
            return lifecycle.expire(exchange)?1L:0L;
        },"HistoryMapper.append","ExchangeLifecycleMapper.lock");
        assertTrue(result.get(0)>0);assertEquals(0L,result.get(1));
        assertEquals(1,appendAttempts.get());assertEquals(1,expiryAttempts.get(),"Foreign-key locks must not create a retry-dependent cycle");
        assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM cl_exchange WHERE id=?",String.class,exchange));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_history WHERE exchange_id=? AND event_type='EXCHANGED'",Integer.class,exchange));
    }

    @Autowired edu.campusloop.web.history.service.HistoryConfirmationService historyConfirmations;
    private record HistoryFixture(long exchange,long item,long event,String evidence) {
        String path() {return "/api/items/"+item+"/history/"+event;}
    }
    private HistoryFixture confirmationFixture(int length,boolean evidence) throws Exception {
        long exchange=ready(length,"confirm-source-"+UUID.randomUUID());int version=almostComplete(exchange,length);
        lifecycle.handoff(List.of(a,b,c).get(length-1).id(),exchange,version,RECEIVED,"已收到");
        long item=jdbc.queryForObject("SELECT offered_item_id FROM cl_exchange_participant WHERE exchange_id=? AND user_id=?",Long.class,exchange,a.id());
        String upload=evidence?evidenceUpload(a,true):null;
        long event=call("POST","/api/items/"+item+"/history",a.token(),statement(length==2?"TRANSFER":"REPAIR","本人认可的自述，非管理员核验",exchange,null,upload==null?List.of():List.of(upload)),200).path("id").asLong();
        return new HistoryFixture(exchange,item,event,upload);
    }
    private JsonNode requestConfirmation(HistoryFixture f) throws Exception {return call("POST",f.path()+"/confirmation-request",a.token(),Map.of("shareEvidenceWithAllParticipants",true),200);}
    private Map<String,Object> confirmationBody(String hash) {return Map.of("snapshotHash",hash,"acknowledged",true);}
    private long confirmHistory(Account actor,HistoryFixture f,String hash) {
        historyConfirmations.act(actor.id(),f.item(),f.event(),"confirm",new edu.campusloop.web.history.dto.HistoryConfirmationCommand(hash,null));return f.event();
    }
    @Test void historyConfirmationTwoAndThreeRequireEveryIndependentParticipantAndPreserveSelfSource() throws Exception {
        for(int length:List.of(2,3)) {
            var f=confirmationFixture(length,true);var original=jdbc.queryForMap("SELECT * FROM cl_item_history WHERE id=?",f.event());
            long b04=jdbc.queryForObject("SELECT id FROM cl_item_history WHERE exchange_id=? AND item_id=? AND event_type='EXCHANGED'",Long.class,f.exchange(),f.item());
            var fact=call("GET","/api/items/"+f.item()+"/history/"+b04,a.token(),null,200);
            assertEquals("B04_HANDOFF",fact.at("/confirmation/mode").asText());assertEquals(length,fact.at("/confirmation/confirmedCount").asInt());assertEquals(length,fact.at("/confirmation/requiredCount").asInt());
            for(var person:fact.at("/confirmation/participants")) {assertFalse(person.path("handedOffAt").isNull());assertFalse(person.path("receivedAt").isNull());assertTrue(person.path("offeredItemId").asLong()>0);assertTrue(person.path("receivedItemId").asLong()>0);}
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_history_confirmation_request WHERE history_id=?",Integer.class,b04));
            var start=requestConfirmation(f);String hash=start.at("/confirmation/snapshotHash").asText();
            assertEquals(b04,start.at("/confirmation/snapshot/exchangeHistoryEventId").asLong());assertEquals("history-confirmation-v1",start.at("/confirmation/snapshot/ruleVersion").asText());
            assertEquals(length,start.at("/confirmation/requiredCount").asInt());assertEquals(0,start.at("/confirmation/confirmedCount").asInt());assertEquals("SELF_REPORTED",start.path("evidenceLevel").asText());
            var rows=businessRows();assertEquals(start,requestConfirmation(f));assertEquals(rows,businessRows());
            for(int i=0;i<length;i++) {
                var actor=List.of(a,b,c).get(i);var view=call("POST",f.path()+"/confirm",actor.token(),confirmationBody(hash),200);
                assertEquals(i+1,view.at("/confirmation/confirmedCount").asInt());assertEquals(i==length-1?"BOTH_CONFIRMED":"SELF_REPORTED",view.path("evidenceLevel").asText());
                assertEquals("SELF_REPORTED",view.path("recordedEvidenceLevel").asText());assertEquals(i==length-1,!view.path("confirmedAt").isNull());
                rows=businessRows();assertEquals(view,call("POST",f.path()+"/confirm",actor.token(),confirmationBody(hash),200));assertEquals(rows,businessRows());
            }
            var publicView=call("GET",f.path(),null,null,200);assertEquals(length,publicView.at("/confirmation/confirmedCount").asInt());assertTrue(publicView.at("/confirmation/snapshot").isNull());assertTrue(publicView.at("/confirmation/participants").isNull());assertTrue(publicView.at("/confirmation/snapshotHash").isNull());assertFalse(publicView.toString().contains(f.evidence()));
            assertEquals(original,jdbc.queryForMap("SELECT * FROM cl_item_history WHERE id=?",f.event()));assertEquals(length,jdbc.queryForObject("SELECT COUNT(*) FROM cl_history_confirmation WHERE history_id=?",Integer.class,f.event()));
            assertEquals("BOTH_CONFIRMED",call("GET",f.path(),admin.token(),null,200).path("evidenceLevel").asText());
        }
    }
    @Test void historyConfirmationRejectsForgedRostersAndHashesAndRequiresExplicitEvidenceConsent() throws Exception {
        var f=confirmationFixture(3,true);evidenceRead(f.evidence(),c,404);
        var rows=businessRows();
        for(var actor:List.of(b,c,outsider,admin)) call("POST",f.path()+"/confirmation-request",actor.token(),Map.of("shareEvidenceWithAllParticipants",true),403);
        for(Object invalid:List.of(Map.of(),Map.of("shareEvidenceWithAllParticipants",false),Map.of("shareEvidenceWithAllParticipants",true,"participantIds",List.of(a.id(),b.id())),Map.of("shareEvidenceWithAllParticipants",true,"exchangeId",f.exchange())))
            call("POST",f.path()+"/confirmation-request",a.token(),invalid,400);
        call("POST",f.path()+"/confirmation-request",null,Map.of("shareEvidenceWithAllParticipants",true),401);assertEquals(rows,businessRows());
        String hash=requestConfirmation(f).at("/confirmation/snapshotHash").asText();evidenceRead(f.evidence(),c,200);evidenceRead(f.evidence(),outsider,404);
        jdbc.update("UPDATE cl_item SET status='HIDDEN' WHERE id=?",f.item());
        var page=call("GET","/api/items/"+f.item()+"/history",c.token(),null,200);assertEquals(1,page.path("total").asInt());assertEquals(f.event(),page.at("/records/0/id").asLong());
        rows=businessRows();
        for(var actor:List.of(outsider,admin)) call("POST",f.path()+"/confirm",actor.token(),confirmationBody(hash),403);
        call("POST",f.path()+"/confirm",b.token(),confirmationBody("0".repeat(64)),409);
        for(String field:List.of("evidenceLevel","sourceLevel","userId","exchangeId","participantIds")) {
            var body=new HashMap<>(confirmationBody(hash));body.put(field,"ADMIN_VERIFIED");call("POST",f.path()+"/confirm",b.token(),body,400);
        }
        call("POST",f.path()+"/confirm",b.token(),Map.of("snapshotHash",hash,"acknowledged",false),400);
        assertEquals(rows,businessRows());
        var path=TEST_UPLOADS.resolveSibling(TEST_UPLOADS.getFileName()+"-evidence").resolve(f.evidence()+".png");var bytes=java.nio.file.Files.readAllBytes(path);
        try {java.nio.file.Files.write(path,new byte[]{1,2,3});call("POST",f.path()+"/confirm",b.token(),confirmationBody(hash),409);} finally {java.nio.file.Files.write(path,bytes);}
        assertEquals(rows,businessRows());
        long unlinked=item(a,1);long event=call("POST","/api/items/"+unlinked+"/history",a.token(),statement("TRANSFER","未关联",null,null,List.of()),200).path("id").asLong();
        call("POST","/api/items/"+unlinked+"/history/"+event+"/confirmation-request",a.token(),Map.of("shareEvidenceWithAllParticipants",true),409);
    }
    @Test void historyConfirmationCorrectionsAndWithdrawalKeepOldSnapshotsWithoutInheritance() throws Exception {
        var f=confirmationFixture(3,true);String hash=requestConfirmation(f).at("/confirmation/snapshotHash").asText();
        for(var actor:List.of(a,b,c)) confirmHistory(actor,f,hash);
        var original=jdbc.queryForMap("SELECT * FROM cl_history_confirmation_request WHERE history_id=?",f.event());
        String newEvidence=evidenceUpload(a,true);
        long next=call("POST","/api/items/"+f.item()+"/history",a.token(),statement("REPAIR","修正后必须重新确认",f.exchange(),f.event(),List.of(newEvidence)),200).path("id").asLong();
        var corrected=call("GET","/api/items/"+f.item()+"/history/"+next,a.token(),null,200);
        assertEquals("SELF_REPORTED",corrected.path("evidenceLevel").asText());assertEquals(0,corrected.at("/confirmation/confirmedCount").asInt());evidenceRead(newEvidence,c,404);
        var old=call("GET",f.path(),a.token(),null,200);assertEquals("SUPERSEDED",old.at("/confirmation/status").asText());assertEquals("BOTH_CONFIRMED",old.path("evidenceLevel").asText());assertEquals(3,old.at("/confirmation/confirmedCount").asInt());
        call("POST",f.path()+"/confirm",a.token(),confirmationBody(hash),409);assertEquals(original,jdbc.queryForMap("SELECT * FROM cl_history_confirmation_request WHERE history_id=?",f.event()));
        var nf=new HistoryFixture(f.exchange(),f.item(),next,newEvidence);String newHash=requestConfirmation(nf).at("/confirmation/snapshotHash").asText();assertNotEquals(hash,newHash);evidenceRead(newEvidence,c,200);
        call("POST",nf.path()+"/confirm",b.token(),confirmationBody(hash),409);confirmHistory(a,nf,newHash);
        var withdraw=Map.of("snapshotHash",newHash,"reason","声明需重新核对");
        call("POST",nf.path()+"/withdraw-confirmation",b.token(),withdraw,403);
        var view=call("POST",nf.path()+"/withdraw-confirmation",a.token(),withdraw,200);assertEquals("WITHDRAWN",view.at("/confirmation/status").asText());assertEquals(1,view.at("/confirmation/confirmedCount").asInt());assertEquals("SELF_REPORTED",view.path("evidenceLevel").asText());
        var rows=businessRows();assertEquals(view,call("POST",nf.path()+"/withdraw-confirmation",a.token(),withdraw,200));assertEquals(rows,businessRows());
        call("POST",nf.path()+"/withdraw-confirmation",a.token(),Map.of("snapshotHash",newHash,"reason","不同原因"),409);
        call("POST",nf.path()+"/confirm",b.token(),confirmationBody(newHash),409);call("POST",nf.path()+"/confirmation-request",a.token(),Map.of("shareEvidenceWithAllParticipants",true),409);
        evidenceRead(newEvidence,c,200);assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM cl_history_confirmation WHERE history_id=?",Integer.class,f.event()));
    }
    @Test void historyConfirmationAtomicAppendFailureAndDatabaseReferencesPreserveSourceChain() throws Exception {
        var f=confirmationFixture(2,false);
        for(String phase:List.of("appendRequest","appendMember","appendConfirmation","appendWithdrawal")) {
            if(phase.equals("appendConfirmation")) requestConfirmation(f);
            var req=context.getBean(edu.campusloop.web.history.mapper.HistoryConfirmationMapper.class).request(f.event());String hash=req==null?null:req.snapshotHash();
            String action=phase.equals("appendConfirmation")?"confirm":phase.equals("appendWithdrawal")?"withdraw":"request";
            var rows=businessRows();var once=new java.util.concurrent.atomic.AtomicBoolean();
            SqlProbe.after.set(id->{if(id.endsWith("HistoryConfirmationMapper."+phase) && once.compareAndSet(false,true)) jdbc.update("INSERT INTO cl_exchange(initiator_id,status,idempotency_key,expires_at) SELECT initiator_id,status,idempotency_key,expires_at FROM cl_exchange WHERE id=?",f.exchange());});
            try {rejected(409,()->historyConfirmations.act(a.id(),f.item(),f.event(),action,new edu.campusloop.web.history.dto.HistoryConfirmationCommand(hash,"核对")));}finally{SqlProbe.after.remove();}
            assertTrue(once.get());assertEquals(rows,businessRows());
        }
        String hash=requestConfirmation(f).at("/confirmation/snapshotHash").asText();confirmHistory(a,f,hash);var rows=businessRows();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO cl_history_confirmation(history_id,user_id,snapshot_hash,confirmed_at) VALUES(?,?,?,CURRENT_TIMESTAMP)",f.event(),outsider.id(),hash));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO cl_history_confirmation(history_id,user_id,snapshot_hash,confirmed_at) VALUES(?,?,?,CURRENT_TIMESTAMP)",f.event(),b.id(),"0".repeat(64)));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO cl_history_confirmation_member(history_id,exchange_id,user_id) VALUES(?,?,?)",f.event(),f.exchange(),outsider.id()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("DELETE FROM cl_history_confirmation_request WHERE history_id=?",f.event()));assertEquals(rows,businessRows());
    }
    @Test void mysqlHistoryFinalConfirmationsAndDuplicateRequestAreSerializedExactlyOnce() throws Exception {
        mysqlOnly();var f=confirmationFixture(3,false);String hash=requestConfirmation(f).at("/confirmation/snapshotHash").asText();confirmHistory(a,f,hash);
        assertEquals(List.of(f.event(),f.event()),race(()->confirmHistory(b,f,hash),()->confirmHistory(c,f,hash),"HistoryConfirmationMapper.appendConfirmation","ExchangeLifecycleMapper.lock"));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM cl_history_confirmation WHERE history_id=?",Integer.class,f.event()));
        var rows=businessRows();confirmHistory(c,f,hash);assertEquals(rows,businessRows());assertEquals("BOTH_CONFIRMED",call("GET",f.path(),null,null,200).path("evidenceLevel").asText());
        var second=confirmationFixture(2,false);
        java.util.concurrent.Callable<Long> start=()->{historyConfirmations.act(a.id(),second.item(),second.event(),"request",new edu.campusloop.web.history.dto.HistoryConfirmationCommand(null,null));return second.event();};
        assertEquals(List.of(second.event(),second.event()),race(start,start,"HistoryConfirmationMapper.appendRequest","ExchangeLifecycleMapper.lock"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM cl_history_confirmation_member WHERE history_id=?",Integer.class,second.event()));
    }
    @Test void mysqlHistoryCorrectionAndConfirmationRespectBothCommitOrders() throws Exception {
        mysqlOnly();
        for(boolean correctionFirst:List.of(true,false)) {
            var f=confirmationFixture(2,false);String hash=requestConfirmation(f).at("/confirmation/snapshotHash").asText();confirmHistory(a,f,hash);
            var body=json.convertValue(statement("REPAIR","新修正",f.exchange(),f.event(),List.of()),edu.campusloop.web.history.dto.HistoryRequest.class);
            var outcome=correctionFirst?race(()->histories.create(a.id(),f.item(),body),()->confirmHistory(b,f,hash),"HistoryMapper.append","ExchangeLifecycleMapper.lock"):
                race(()->confirmHistory(b,f,hash),()->histories.create(a.id(),f.item(),body),"HistoryConfirmationMapper.appendConfirmation","ExchangeLifecycleMapper.lock");
            assertTrue(outcome.get(0)>0);if(correctionFirst) assertEquals(-409L,outcome.get(1));else assertTrue(outcome.get(1)>0);
            var old=call("GET",f.path(),a.token(),null,200);assertEquals("SUPERSEDED",old.at("/confirmation/status").asText());assertEquals(correctionFirst?1:2,old.at("/confirmation/confirmedCount").asInt());
            long next=old.path("correctedByEventId").asLong();assertEquals("SELF_REPORTED",call("GET","/api/items/"+f.item()+"/history/"+next,a.token(),null,200).path("evidenceLevel").asText());
        }
    }
    @Test void mysqlHistoryWithdrawalAndLastConfirmationKeepExactHistoricalOutcome() throws Exception {
        mysqlOnly();
        for(boolean withdrawFirst:List.of(true,false)) {
            var f=confirmationFixture(2,false);String hash=requestConfirmation(f).at("/confirmation/snapshotHash").asText();confirmHistory(a,f,hash);
            java.util.concurrent.Callable<Long> withdraw=()->{historyConfirmations.act(a.id(),f.item(),f.event(),"withdraw",new edu.campusloop.web.history.dto.HistoryConfirmationCommand(hash,"待核对"));return f.event();};
            var result=withdrawFirst?race(withdraw,()->confirmHistory(b,f,hash),"HistoryConfirmationMapper.appendWithdrawal","ExchangeLifecycleMapper.lock"):
                race(()->confirmHistory(b,f,hash),withdraw,"HistoryConfirmationMapper.appendConfirmation","ExchangeLifecycleMapper.lock");
            assertEquals(List.of(f.event(),withdrawFirst?-409L:f.event()),result);
            var view=call("GET",f.path(),a.token(),null,200);assertEquals("WITHDRAWN",view.at("/confirmation/status").asText());assertEquals(withdrawFirst?"SELF_REPORTED":"BOTH_CONFIRMED",view.path("evidenceLevel").asText());
        }
    }

    @Autowired edu.campusloop.web.history.service.HistoryVerificationService verifications;
    private String verificationPath(long event) {return "/api/admin/history-verifications/"+event;}
    private Map<String,Object> verificationBody(long event,Account actor,String decision,String key) throws Exception {
        var detail=call("GET",verificationPath(event),actor.token(),null,200);
        return Map.of("version",detail.path("version").asInt(),"snapshotHash",detail.path("snapshotHash").asText(),"idempotencyKey",key,"decision",decision,"scope","仅核对此事件提供材料是否支持声明","reason","虚构私有核验理由，不公开");
    }
    private long decideHistory(Account actor,long event,Map<String,Object> body) {
        verifications.decide(actor.id(),event,edu.campusloop.web.history.dto.HistoryVerificationCommand.parse(json.valueToTree(body)));return event;
    }
    @Test void adminVerificationPreservesOriginalAndParticipantSourcesAndProtectsPrivateAudit() throws Exception {
        var f=confirmationFixture(3,true);String hash=requestConfirmation(f).at("/confirmation/snapshotHash").asText();confirmHistory(a,f,hash);confirmHistory(b,f,hash);
        var source=jdbc.queryForMap("SELECT * FROM cl_item_history WHERE id=?",f.event());var body=verificationBody(f.event(),admin,"APPROVED","approve-self");
        var rows=businessRows();call("GET","/api/admin/history-verifications?size=1",admin.token(),null,200);assertEquals(rows,businessRows());
        var result=call("POST",verificationPath(f.event())+"/decision",admin.token(),body,200);assertEquals(1,result.path("version").asInt());assertEquals("APPROVED",result.path("status").asText());
        rows=businessRows();assertEquals(result,call("POST",verificationPath(f.event())+"/decision",admin.token(),body,200));assertEquals(rows,businessRows());
        var publicView=call("GET",f.path(),null,null,200);assertEquals("ADMIN_VERIFIED",publicView.path("evidenceLevel").asText());assertEquals("SELF_REPORTED",publicView.path("recordedEvidenceLevel").asText());assertEquals(2,publicView.at("/confirmation/confirmedCount").asInt());
        for(String field:List.of("reason","snapshot","snapshotHash","adminId")) assertTrue(publicView.at("/verification/"+field).isNull());assertFalse(publicView.toString().contains(f.evidence()));assertFalse(publicView.toString().contains("虚构私有核验理由"));assertFalse(publicView.path("verifiedAt").isNull());
        assertEquals(source,jdbc.queryForMap("SELECT * FROM cl_item_history WHERE id=?",f.event()));assertTrue(result.at("/verification/snapshot/evidence/0/sha256").isTextual());
        long fact=jdbc.queryForObject("SELECT id FROM cl_item_history WHERE exchange_id=? AND item_id=? AND event_type='EXCHANGED'",Long.class,f.exchange(),f.item());
        var factBody=verificationBody(fact,admin,"APPROVED","approve-real-fact");call("POST",verificationPath(fact)+"/decision",admin.token(),factBody,200);
        assertEquals("BOTH_CONFIRMED",jdbc.queryForObject("SELECT evidence_level FROM cl_item_history WHERE id=?",String.class,fact));
    }
    @Test void verificationRejectionAndCorrectionCannotOverwritePriorDecisionOrInheritLevel() throws Exception {
        var f=confirmationFixture(2,false);var approve=verificationBody(f.event(),admin,"APPROVED","no-proof");call("POST",verificationPath(f.event())+"/decision",admin.token(),approve,409);
        var reject=verificationBody(f.event(),admin,"REJECTED","rejected");call("POST",verificationPath(f.event())+"/decision",admin.token(),reject,200);assertEquals("SELF_REPORTED",call("GET",f.path(),a.token(),null,200).path("evidenceLevel").asText());
        var original=jdbc.queryForMap("SELECT * FROM cl_history_verification_audit WHERE history_id=?",f.event());
        var changed=new HashMap<>(reject);changed.put("reason","不同内容");call("POST",verificationPath(f.event())+"/decision",admin.token(),changed,409);
        changed=new HashMap<>(reject);changed.put("idempotencyKey","different-key");call("POST",verificationPath(f.event())+"/decision",admin.token(),changed,409);
        String evidence=evidenceUpload(a,true);long next=call("POST","/api/items/"+f.item()+"/history",a.token(),statement("REPAIR","修正后新对象",f.exchange(),f.event(),List.of(evidence)),200).path("id").asLong();
        assertEquals(0,call("GET",verificationPath(next),admin.token(),null,200).path("version").asInt());
        assertEquals("SELF_REPORTED",call("GET","/api/items/"+f.item()+"/history/"+next,null,null,200).path("evidenceLevel").asText());assertEquals(original,jdbc.queryForMap("SELECT * FROM cl_history_verification_audit WHERE history_id=?",f.event()));
        call("POST",verificationPath(next)+"/decision",admin.token(),reject,409);
        var fresh=verificationBody(next,admin,"APPROVED","fresh-correction");call("POST",verificationPath(next)+"/decision",admin.token(),fresh,200);
        assertEquals(1,call("GET","/api/admin/history-verifications?status=REJECTED",admin.token(),null,200).path("total").asInt());
        for(String params:List.of("size=101","page=0","status=FAKE","page=1&page=2","ownerId=1")) call("GET","/api/admin/history-verifications?"+params,admin.token(),null,400);
    }
    @Test void verificationRejectsUsersInterestsForgedFieldsStaleSnapshotsAndDisabledAdmins() throws Exception {
        var f=confirmationFixture(3,true);var body=verificationBody(f.event(),admin,"APPROVED","authorization");var before=businessRows();
        for(var user:List.of(a,b,outsider)) {call("GET",verificationPath(f.event()),user.token(),null,403);call("POST",verificationPath(f.event())+"/decision",user.token(),body,403);}
        call("GET",verificationPath(f.event()),null,null,401);
        for(String field:List.of("adminId","evidenceLevel","sourceLevel","ownerId","verifiedAt")) {var forged=new HashMap<>(body);forged.put(field,"ADMIN_VERIFIED");call("POST",verificationPath(f.event())+"/decision",admin.token(),forged,400);}
        var stale=new HashMap<>(body);stale.put("version",9);call("POST",verificationPath(f.event())+"/decision",admin.token(),stale,409);
        assertEquals(before,businessRows());
        String hash=requestConfirmation(f).at("/confirmation/snapshotHash").asText();confirmHistory(a,f,hash);call("POST",verificationPath(f.event())+"/decision",admin.token(),body,409);
        jdbc.update("UPDATE cl_user SET role='ADMIN',admin_permissions='ALL' WHERE id=?",b.id());var involved=verificationBody(f.event(),b,"APPROVED","involved");call("POST",verificationPath(f.event())+"/decision",b.token(),involved,403);
        long ownItem=item(admin,1);long own=call("POST","/api/items/"+ownItem+"/history",admin.token(),statement("REPAIR","本人声明",null,null,List.of()),200).path("id").asLong();call("POST",verificationPath(own)+"/decision",admin.token(),verificationBody(own,admin,"REJECTED","own"),403);
        jdbc.update("UPDATE cl_user SET status='DISABLED' WHERE id=?",admin.id());rejected(401,()->decideHistory(admin,f.event(),body));jdbc.update("UPDATE cl_user SET status='ACTIVE' WHERE id=?",admin.id());
    }
    @Test void verificationSqlFailureRollsBackStateAndAppendAuditAtEveryWriteStage() throws Exception {
        var f=confirmationFixture(2,true);var body=verificationBody(f.event(),admin,"APPROVED","rollback");
        for(String phase:List.of("prepare","decide","append")) {
            var rows=businessRows();var once=new java.util.concurrent.atomic.AtomicBoolean();
            SqlProbe.after.set(id->{if(id.endsWith("HistoryVerificationMapper."+phase) && once.compareAndSet(false,true)) jdbc.update("INSERT INTO cl_exchange(initiator_id,status,idempotency_key,expires_at) SELECT initiator_id,status,idempotency_key,expires_at FROM cl_exchange WHERE id=?",f.exchange());});
            try{rejected(409,()->decideHistory(admin,f.event(),body));}finally{SqlProbe.after.remove();}assertTrue(once.get());assertEquals(rows,businessRows());
        }
        decideHistory(admin,f.event(),body);var rows=businessRows();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("DELETE FROM cl_history_verification_state WHERE history_id=?",f.event()));
        var constraint=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE cl_history_verification_state SET version=2 WHERE history_id=?",f.event()));
        assertTrue(constraint.getMostSpecificCause().getMessage().toLowerCase(java.util.Locale.ROOT).contains("ck_verification_state"));assertEquals(rows,businessRows());
    }
    @Test void mysqlAdminDecisionsRaceWithoutOverwriteAndSameRequestRetriesOnce() throws Exception {
        mysqlOnly();var other=account(true);var f=confirmationFixture(2,true);
        var one=verificationBody(f.event(),admin,"APPROVED","race-one");var two=verificationBody(f.event(),other,"REJECTED","race-two");
        assertEquals(List.of(f.event(),-409L),race(()->decideHistory(admin,f.event(),one),()->decideHistory(other,f.event(),two),"HistoryVerificationMapper.decide","ExchangeLifecycleMapper.lock"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_history_verification_audit WHERE history_id=?",Integer.class,f.event()));
        var second=confirmationFixture(2,true);var repeat=verificationBody(second.event(),admin,"APPROVED","same-request");
        assertEquals(List.of(second.event(),second.event()),race(()->decideHistory(admin,second.event(),repeat),()->decideHistory(admin,second.event(),repeat),"HistoryVerificationMapper.decide","ExchangeLifecycleMapper.lock"));
    }
    @Test void mysqlAdminVerificationAndCorrectionRespectBothCommitOrders() throws Exception {
        mysqlOnly();
        for(boolean correctionFirst:List.of(true,false)) {
            var f=confirmationFixture(2,true);var body=verificationBody(f.event(),admin,"APPROVED","correction-"+correctionFirst);
            var correction=json.convertValue(statement("REPAIR","核验后不继承的新修正",f.exchange(),f.event(),List.of(f.evidence())),edu.campusloop.web.history.dto.HistoryRequest.class);
            var result=correctionFirst?race(()->histories.create(a.id(),f.item(),correction),()->decideHistory(admin,f.event(),body),"HistoryMapper.append","ExchangeLifecycleMapper.lock"):
                race(()->decideHistory(admin,f.event(),body),()->histories.create(a.id(),f.item(),correction),"HistoryVerificationMapper.decide","ExchangeLifecycleMapper.lock");
            if(correctionFirst) assertEquals(-409L,result.get(1));else assertTrue(result.get(1)>0);
            var old=call("GET",f.path(),a.token(),null,200);long next=old.path("correctedByEventId").asLong();assertTrue(next>0);
            assertEquals("SELF_REPORTED",call("GET","/api/items/"+f.item()+"/history/"+next,a.token(),null,200).path("evidenceLevel").asText());
            assertEquals(correctionFirst?"SELF_REPORTED":"ADMIN_VERIFIED",old.path("evidenceLevel").asText());
        }
    }

    @Autowired ExchangeDisputeQueryService disputeQueries;
    @Test void adminDisputeTracePreservesTwoAndThreePartyDirectionsAndNeverWrites() throws Exception {
        for(int length:List.of(2,3)) {
            long id=ready(length,"admin-trace-"+length);
            lifecycle.handoff(a.id(),id,length,GIVEN,"不公开的交接说明");
            lifecycle.dispute(b.id(),id,length+1,"虚构物品异常");
            var before=businessRows();String path="/api/admin/exchange-disputes/"+id;
            var detail=call("GET",path,admin.token(),null,200);
            assertEquals(length,detail.path("participants").size());assertEquals(length,detail.path("flows").size());
            assertEquals("DISPUTED",detail.path("status").asText());assertTrue(detail.path("allowedActions").isEmpty());
            assertFalse(detail.toString().contains("不公开的交接说明"));
            for(var person:detail.path("participants")) {
                assertTrue(person.path("handedOffNote").isNull());assertTrue(person.path("receivedNote").isNull());
                assertTrue(detail.path("flows").findValues("fromUserId").contains(person.path("userId")));
            }
            var first=call("GET",path+"/events?size=1",admin.token(),null,200);
            assertEquals(length+2,first.path("total").asInt());assertEquals(1,first.path("records").size());
            assertEquals(1,first.at("/records/0/newVersion").asInt());
            var all=call("GET",path+"/events",admin.token(),null,200);
            assertEquals("DISPUTED",all.path("records").get(length+1).path("eventType").asText());
            assertEquals("虚构物品异常",all.path("records").get(length+1).path("reason").asText());
            assertFalse(all.toString().contains("不公开的交接说明"));
            assertTrue(all.at("/records/0/occurredAt").asText().endsWith("Z"));
            assertTrue(call("GET",path+"/events?page=99",admin.token(),null,200).path("records").isEmpty());
            assertEquals(before,businessRows());
        }
        var queue=call("GET","/api/admin/exchange-disputes?size=1",admin.token(),null,200);
        assertEquals(2,queue.path("total").asInt());assertEquals(1,queue.path("records").size());
        assertNotEquals(queue.at("/records/0/id"),call("GET","/api/admin/exchange-disputes?size=1&page=2",admin.token(),null,200).at("/records/0/id"));
    }
    @Test void adminDisputeReadsRejectUsersForgedFiltersAndNonDisputedTargets() throws Exception {
        long id=ready(2,"admin-read-auth");String root="/api/admin/exchange-disputes";var before=businessRows();
        for(String path:List.of(root,root+"/"+id,root+"/"+id+"/events")) {
            call("GET",path,null,null,401);call("GET",path,a.token(),null,403);call("GET",path,outsider.token(),null,403);
        }
        assertTrue(call("GET",root,admin.token(),null,200).path("records").isEmpty());
        call("GET",root+"/"+id,admin.token(),null,404);call("GET",root+"/"+id+"/events",admin.token(),null,404);
        call("GET",root+"/0",admin.token(),null,400);call("GET",root+"/9223372036854775807",admin.token(),null,404);
        for(String params:List.of("size=101","page=0","size=-1","page=1&page=2","ownerId=1","status=READY","version=0","page=999999999999"))
            call("GET",root+"?"+params,admin.token(),null,400);
        rejected(403,()->{disputeQueries.page(a.id(),1,12);});assertEquals(before,businessRows());
        jdbc.update("UPDATE cl_user SET status='DISABLED' WHERE id=?",admin.id());
        rejected(401,()->{disputeQueries.detail(admin.id(),id);});
        jdbc.update("UPDATE cl_user SET status='ACTIVE' WHERE id=?",admin.id());
    }

    private Map<String,List<Map<String,Object>>> businessRows() {
        Map<String,List<Map<String,Object>>> values=new LinkedHashMap<>();
        for(String table:List.of("cl_item","cl_demand","cl_exchange","cl_exchange_participant","cl_exchange_event","cl_item_history","cl_upload")) values.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY id"));
        values.put("cl_history_evidence",jdbc.queryForList("SELECT * FROM cl_history_evidence ORDER BY history_id,upload_id"));
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
