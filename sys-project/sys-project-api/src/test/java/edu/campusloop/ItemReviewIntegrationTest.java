package edu.campusloop;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles(resolver = CampusIntegrationTest.Profile.class)
class ItemReviewIntegrationTest {
    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry registry) {
        boolean mysql = Boolean.getBoolean("campus.mysql-test");
        String url = mysql ? System.getenv("TEST_DB_URL") :
            "jdbc:h2:mem:campus_loop_item_review_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        String username = mysql ? System.getenv("TEST_DB_USERNAME") : "sa";
        String password = mysql ? System.getenv("TEST_DB_PASSWORD") : "";
        if (mysql && (url == null || !url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/campus_loop_[a-z0-9_]*test(?:\\?.*)?")))
            throw new IllegalStateException("Item review tests require an explicitly isolated localhost campus_loop_*test schema");
        if (username == null || password == null) throw new IllegalStateException("Explicit test credentials are required");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> username);
        registry.add("spring.flyway.password", () -> password);
        registry.add("campus.bootstrap-enabled", () -> false);
        registry.add("campus.registration-mode", () -> "DEVELOPMENT_SELF_SERVICE");
        registry.add("campus.jwt-secret", () -> UUID.randomUUID().toString() + UUID.randomUUID());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private final List<Long> userIds = new ArrayList<>();
    private Account admin, owner, other;
    private String prefix;
    private record Account(long id, String token) {}
    @BeforeEach void fixtures() throws Exception {
        prefix = "review_" + UUID.randomUUID().toString().substring(0,8);
        admin = account(true); owner = account(false); other = account(false);
    }
    @AfterEach void removeOnlyFixtures() {
        for (long id : userIds) jdbc.update("DELETE FROM cl_item_review_audit WHERE item_id IN (SELECT id FROM cl_item WHERE owner_id=?)",id);
        for (long id : userIds) {
            jdbc.update("DELETE FROM cl_demand_item WHERE demand_id IN (SELECT id FROM cl_demand WHERE owner_id=?)",id);
            jdbc.update("DELETE FROM cl_demand WHERE owner_id=?",id);
            jdbc.update("DELETE FROM cl_item WHERE owner_id=?",id);
            jdbc.update("DELETE FROM cl_notification WHERE user_id=?",id);
            jdbc.update("DELETE FROM cl_auth_session WHERE user_id=?",id);
            jdbc.update("DELETE FROM cl_user WHERE id=?",id);
        }
    }
    @Test void relistingRequiresOwnerVersionAndReviewBeforePublication() throws Exception {
        long id=item(owner);jdbc.update("UPDATE cl_item SET status='HIDDEN' WHERE id=?",id);
        call("POST","/api/items/"+id+"/relist",other.token(),Map.of("version",0),403);
        var result=call("POST","/api/items/"+id+"/relist",owner.token(),Map.of("version",0),200);
        assertEquals("PENDING_REVIEW",result.path("status").asText());assertEquals(1,result.path("version").asInt());
        call("GET","/api/items/"+id,null,null,404);call("POST","/api/items/"+id+"/relist",owner.token(),Map.of("version",0),409);
        call("POST",review(id),admin.token(),Map.of("version",1,"decision","APPROVE","reason","可交换"),200);
        call("GET","/api/items/"+id,null,null,200);
        assertEquals(1,call("GET","/api/notifications/unread-count",owner.token(),null,200).asInt());
    }
    @Test void adminQueriesDoNotWidenPublicOrOwnerAccess() throws Exception {
        for (String state : List.of("DRAFT","PENDING_REVIEW","HIDDEN")) {
            long id = item(owner);
            jdbc.update("UPDATE cl_item SET status=? WHERE id=?",state,id);
            JsonNode detail = call("GET","/api/admin/items/"+id,admin.token(),null,200);
            assertEquals(state,detail.path("status").asText()); assertEquals(id,detail.path("id").asLong());
            call("GET","/api/items/"+id,null,null,404);
            call("GET","/api/items/mine/"+id,other.token(),null,403);
            call("GET","/api/items/mine/"+id,owner.token(),null,200);
            call("GET","/api/admin/items/"+id,owner.token(),null,403);
            call("GET","/api/admin/items/"+id,null,null,401);
            JsonNode page=call("GET","/api/admin/items?keyword="+prefix+"&status="+state,admin.token(),null,200);
            assertEquals(1,page.path("total").asInt()); assertEquals(id,page.at("/records/0/id").asLong());
        }
        call("GET","/api/admin/items",other.token(),null,403);
    }
    @Test void adminPaginationCountsMatchingRecordsAndRejectsInvalidParameters() throws Exception {
        item(owner); item(other);
        JsonNode first=call("GET","/api/admin/items?keyword="+prefix+"&size=1",admin.token(),null,200);
        assertEquals(2,first.path("total").asInt()); assertEquals(1,first.path("records").size());
        JsonNode second=call("GET","/api/admin/items?keyword="+prefix+"&size=1&page=2",admin.token(),null,200);
        assertNotEquals(first.at("/records/0/id"),second.at("/records/0/id"));
        JsonNode beyond=call("GET","/api/admin/items?keyword="+prefix+"&page=9",admin.token(),null,200);
        assertTrue(beyond.path("records").isEmpty()); assertEquals(2,beyond.path("total").asInt());
        JsonNode empty=call("GET","/api/admin/items?keyword="+prefix+"missing",admin.token(),null,200);
        assertTrue(empty.path("records").isEmpty()); assertEquals(0,empty.path("total").asInt());
        for (String query:List.of("?page=0","?size=101","?categoryId=0","?status=APPROVED","?keyword="+"a".repeat(101)))
            call("GET","/api/admin/items"+query,admin.token(),null,400);
        call("GET","/api/admin/items/0",admin.token(),null,400);
        call("GET","/api/admin/items/999999999",admin.token(),null,404);
    }
    @Test void publicationDecisionResubmissionAndAuditReadBackAreAtomicAndPrivate() throws Exception {
        long id=item(owner);
        JsonNode submitted=own(id); assertEquals("PENDING_REVIEW",submitted.path("status").asText());
        assertEquals("UNREVIEWED",submitted.path("reviewBasis").asText()); assertEquals(0,submitted.path("version").asInt());
        call("GET","/api/items/"+id,null,null,404);
        for(String token:List.of(owner.token(),other.token())) {
            call("POST",review(id),token,decision(0,"APPROVE"),403);
            call("GET",auditPath(id),token,null,403);
        }
        call("POST",review(id),null,decision(0,"REJECT"),401);
        JsonNode rejected=call("POST",review(id),admin.token(),decision(0,"REJECT"),200);
        assertEquals("REJECTED",rejected.path("status").asText());assertEquals(1,rejected.path("version").asInt());
        assertEquals("审核理由",rejected.path("reviewReason").asText());assertEquals(0,rejected.path("reviewedVersion").asInt());
        assertEquals(rejected,own(id));call("GET","/api/items/"+id,null,null,404);
        call("POST",review(id),admin.token(),decision(1,"APPROVE"),409);
        Map<String,Object> edited=new HashMap<>(itemBody());edited.put("title",prefix+" corrected");edited.put("version",1);
        call("PUT","/api/items/"+id,other.token(),edited,403);
        JsonNode resubmitted=call("PUT","/api/items/"+id,owner.token(),edited,200);
        assertEquals("PENDING_REVIEW",resubmitted.path("status").asText());assertEquals(2,resubmitted.path("version").asInt());
        assertEquals("REJECT",resubmitted.path("reviewDecision").asText()); // Clearly last decision on version 0, not this submission.
        JsonNode approved=call("POST",review(id),admin.token(),decision(2,"APPROVE"),200);
        assertEquals("AVAILABLE",approved.path("status").asText());assertEquals("ADMIN_REVIEW",approved.path("reviewBasis").asText());
        assertEquals(approved,own(id)); assertEquals(approved,call("GET","/api/admin/items/"+id,admin.token(),null,200));
        assertEquals(3,jdbc.queryForObject("SELECT version FROM cl_item WHERE id=?",Integer.class,id));
        assertEquals(prefix+" corrected",jdbc.queryForObject("SELECT title FROM cl_item WHERE id=?",String.class,id));
        JsonNode publicItem=call("GET","/api/items/"+id,null,null,200);
        for(String field:List.of("reviewReason","reviewDecision","reviewedByName","reviewedAt","reviewedVersion")) assertTrue(publicItem.path(field).isNull());
        JsonNode audits=call("GET",auditPath(id)+"?size=2",admin.token(),null,200);
        assertEquals(4,audits.path("total").asInt());assertEquals(2,audits.path("records").size());
        JsonNode editAudit=audits.at("/records/1");
        assertEquals("SUBMIT",editAudit.path("action").asText());
        assertEquals(prefix+" 物品",editAudit.at("/previousSnapshot/title").asText());
        assertEquals(prefix+" corrected",editAudit.at("/newSnapshot/title").asText());
        assertEquals(owner.id(),editAudit.path("operatorUserId").asLong());
        assertTrue(call("GET",auditPath(id)+"?page=99",admin.token(),null,200).path("records").isEmpty());
        for(String query:List.of("?page=0","?size=101")) call("GET",auditPath(id)+query,admin.token(),null,400);
        call("GET",auditPath(999999999),admin.token(),null,404);
        call("POST","/api/items/"+id+"/withdraw",owner.token(),Map.of("version",3),200);
        assertEquals("WITHDRAW",call("GET",auditPath(id),admin.token(),null,200).at("/records/0/action").asText());
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_review_audit WHERE item_id=?",Integer.class,id));
    }
    @Test void approvalValidationAndStateProtectionNeverChangeRecordOrAudit() throws Exception {
        long id=item(owner);
        for(Object body:List.of(Map.of(),Map.of("version",0,"decision","APPROVE"),Map.of("version",0,"decision","APPROVE","reason"," "),
            Map.of("version",0,"decision","APPROVE","reason","a".repeat(1001)),Map.of("version","0","decision","APPROVE","reason","x"),
            Map.of("version",0,"decision","HIDDEN","reason","x"),Map.of("version",0,"decision","REJECT","reason","x","ownerId",owner.id())))
            call("POST",review(id),admin.token(),body,400);
        call("POST",review(0),admin.token(),decision(0,"APPROVE"),400);
        call("POST",review(999999999),admin.token(),decision(0,"APPROVE"),404);
        for(String state:List.of("DRAFT","AVAILABLE","RESERVED","EXCHANGED","HIDDEN","REJECTED")) {
            jdbc.update("UPDATE cl_item SET status=? WHERE id=?",state,id);
            call("POST",review(id),admin.token(),decision(0,"APPROVE"),409);
            call("POST",review(id),admin.token(),decision(0,"REJECT"),409);
            assertEquals(state,own(id).path("status").asText());
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_review_audit WHERE item_id=?",Integer.class,id));
        for(String field:List.of("status","reviewBasis","reviewReason","reviewDecision","reviewedVersion","reviewedByName")) {
            Map<String,Object> body=new HashMap<>(itemBody());body.put(field,"APPROVE");
            call("POST","/api/items",owner.token(),body,400);body.put("version",0);
            call("PUT","/api/items/"+id,owner.token(),body,400);
        }
    }
    @Test void twoAdminsAndEditAgainstReviewHaveOnlyOneVersionWinner() throws Exception {
        Account secondAdmin=account(true);
        long id=item(owner);
        List<Integer> statuses=race(() -> raw("POST",review(id),admin.token(),decision(0,"APPROVE")),
            () -> raw("POST",review(id),secondAdmin.token(),decision(0,"REJECT")));
        assertEquals(List.of(200,409),statuses.stream().sorted().toList());
        assertEquals(1,own(id).path("version").asInt());assertEquals(2,call("GET",auditPath(id),admin.token(),null,200).path("total").asInt());
        JsonNode last=call("GET",auditPath(id),admin.token(),null,200).at("/records/0");
        assertEquals(last.path("newStatus"),own(id).path("status"));
        long pending=item(owner);Map<String,Object> edit=new HashMap<>(itemBody());edit.put("version",0);edit.put("title",prefix+" race");
        assertEquals(List.of(200,409),race(() -> raw("POST",review(pending),admin.token(),decision(0,"APPROVE")),
            () -> raw("PUT","/api/items/"+pending,owner.token(),edit)).stream().sorted().toList());
        JsonNode current=own(pending);assertEquals(1,current.path("version").asInt());
        assertEquals(current.path("title"),call("GET",auditPath(pending),admin.token(),null,200).at("/records/0/newSnapshot/title"));
    }
    @Test void reviewAndEditsCannotBypassHoldsOrActiveHandoffsEvenWhenStatusIsStale() throws Exception {
        long id=item(owner);
        String key=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO cl_exchange(initiator_id,status,version,idempotency_key,expires_at) VALUES (?,'READY',0,?,CURRENT_TIMESTAMP)",owner.id(),key);
        long exchange=jdbc.queryForObject("SELECT id FROM cl_exchange WHERE idempotency_key=?",Long.class,key);
        try {
            for(String state:List.of("PENDING_REVIEW","AVAILABLE","REJECTED")) {
                jdbc.update("UPDATE cl_item SET status=? WHERE id=?",state,id);
                jdbc.update("INSERT INTO cl_item_hold(item_id,exchange_id,expires_at) VALUES (?,?,'2020-01-01 00:00:00')",id,exchange);
                blockedWrites(id);jdbc.update("DELETE FROM cl_item_hold WHERE item_id=?",id);
            }
            jdbc.update("UPDATE cl_item SET status='PENDING_REVIEW' WHERE id=?",id);
            jdbc.update("INSERT INTO cl_exchange_participant(exchange_id,user_id,offered_item_id,recipient_user_id,handed_off_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP)",exchange,owner.id(),id,other.id());
            for(String status:List.of("AWAITING_CONFIRMATION","READY","DISPUTED")) {
                jdbc.update("UPDATE cl_exchange SET status=? WHERE id=?",status,exchange);blockedWrites(id);
            }
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange_participant WHERE exchange_id=?",Integer.class,exchange));
            assertEquals(0,own(id).path("version").asInt());
            assertEquals(1,call("GET",auditPath(id),admin.token(),null,200).path("total").asInt());
        } finally {
            jdbc.update("DELETE FROM cl_item_hold WHERE exchange_id=?",exchange);
            jdbc.update("DELETE FROM cl_exchange_participant WHERE exchange_id=?",exchange);
            jdbc.update("DELETE FROM cl_exchange WHERE id=?",exchange);
        }
    }
    @Test void editingPublishedItemLeavesFavoritesAsPrivatePlaceholderAndBothCandidateSourcesExcludeIt() throws Exception {
        long id=item(owner);call("POST",review(id),admin.token(),decision(0,"APPROVE"),200);
        call("PUT","/api/items/"+id+"/favorite",other.token(),null,200);
        JsonNode demand=call("POST","/api/demands",owner.token(),Map.of("categoryId",2,"description","隔离需求","preferredTags",List.of(),"offeredItemIds",List.of(id)),200);
        try {
            assertTrue(itemService.availableForMatching().stream().anyMatch(i->i.id()==id));
            assertTrue(matchingSnapshot.snapshot().offers().stream().anyMatch(i->i.id()==id));
            Map<String,Object> edit=new HashMap<>(itemBody());edit.put("version",1);
            call("PUT","/api/items/"+id,owner.token(),edit,200);
            assertFalse(itemService.availableForMatching().stream().anyMatch(i->i.id()==id));
            assertFalse(matchingSnapshot.snapshot().offers().stream().anyMatch(i->i.id()==id));
            JsonNode favorite=call("GET","/api/favorites",other.token(),null,200);
            assertEquals(1,favorite.path("total").asInt());assertFalse(favorite.at("/records/0/itemVisible").asBoolean());assertTrue(favorite.at("/records/0/item").isNull());
            assertFalse(call("GET","/api/demands/"+demand.path("id").asLong(),owner.token(),null,200).at("/offeredItems/0/offerable").asBoolean());
            call("POST",review(id),admin.token(),decision(2,"REJECT"),200);
            assertFalse(itemService.availableForMatching().stream().anyMatch(i->i.id()==id));
            assertFalse(matchingSnapshot.snapshot().offers().stream().anyMatch(i->i.id()==id));
        } finally {
            jdbc.update("DELETE FROM cl_favorite WHERE item_id=?",id);
            jdbc.update("DELETE FROM cl_demand_item WHERE demand_id=?",demand.path("id").asLong());
            jdbc.update("DELETE FROM cl_demand WHERE id=?",demand.path("id").asLong());
        }
    }
    @Test void databaseConstraintsProtectAuditVersionReasonAndReferences() throws Exception {
        long id=item(owner);
        call("POST",review(id),admin.token(),decision(0,"APPROVE"),200);
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE cl_item SET status='APPROVED' WHERE id=?",id));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE cl_item SET review_basis='FORGED' WHERE id=?",id));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("DELETE FROM cl_item WHERE id=?",id));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("DELETE FROM cl_user WHERE id=?",admin.id()));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO cl_item_review_audit(item_id,operator_user_id,operator_display_name,action,reason,previous_status,new_status,previous_version,new_version,new_snapshot_json) SELECT item_id,operator_user_id,operator_display_name,action,reason,previous_status,new_status,previous_version,new_version,new_snapshot_json FROM cl_item_review_audit WHERE item_id=? AND new_version=1",id));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE cl_item_review_audit SET reason=NULL WHERE item_id=? AND action='APPROVE'",id));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE cl_item_review_audit SET previous_version=NULL WHERE item_id=? AND action='APPROVE'",id));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE cl_item_review_audit SET new_version=10 WHERE item_id=? AND action='APPROVE'",id));
    }
    @Autowired edu.campusloop.web.item.service.ItemService itemService;
    @Autowired edu.campusloop.web.matching.service.IndependentMatchingSnapshotService matchingSnapshot;
    private void blockedWrites(long id) throws Exception {
        for(String action:List.of("APPROVE","REJECT")) call("POST",review(id),admin.token(),decision(0,action),409);
        Map<String,Object> edit=new HashMap<>(itemBody());edit.put("version",0);
        call("PUT","/api/items/"+id,owner.token(),edit,409);
        call("POST","/api/items/"+id+"/withdraw",owner.token(),Map.of("version",0),409);
    }
    private JsonNode own(long id) throws Exception {return call("GET","/api/items/mine/"+id,owner.token(),null,200);}
    private String review(long id) {return "/api/admin/items/"+id+"/review";}
    private String auditPath(long id) {return "/api/admin/items/"+id+"/review-audits";}
    private Map<String,Object> decision(int version,String action) {return Map.of("version",version,"decision",action,"reason","  审核理由  ");}
    private List<Integer> race(Callable<MvcResult> first,Callable<MvcResult> second) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try {
            List<Future<Integer>> futures=new ArrayList<>();
            for(Callable<MvcResult> task:List.of(first,second)) futures.add(pool.submit(()->{ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));return task.call().getResponse().getStatus();}));
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            return List.of(futures.get(0).get(20,TimeUnit.SECONDS),futures.get(1).get(20,TimeUnit.SECONDS));
        } finally {start.countDown();pool.shutdownNow();}
    }
    private Account account(boolean administrator) throws Exception {
        String name=prefix+UUID.randomUUID().toString().substring(0,8), password=UUID.randomUUID().toString();
        long id=call("POST","/api/auth/register",null,Map.of("username",name,"password",password,"displayName","隔离审核测试"),200).path("id").asLong();
        userIds.add(id);
        if(administrator) jdbc.update("UPDATE cl_user SET role='ADMIN',admin_permissions='ALL' WHERE id=?",id);
        return new Account(id,call("POST","/api/auth/login",null,Map.of("username",name,"password",password),200).path("token").asText());
    }
    private Map<String,Object> itemBody() {
        return Map.of("title",prefix+" 物品","description","虚构审核物品","categoryId",1,"conditionLevel",4,
            "tags",List.of(),"wantedCategoryId",2,"wantedTags",List.of());
    }
    private long item(Account account) throws Exception {return call("POST","/api/items",account.token(),itemBody(),200).path("id").asLong();}
    private MvcResult raw(String method,String path,String token,Object body) throws Exception {
        var request=request(HttpMethod.valueOf(method),path);
        if(token!=null) request.header("Authorization","Bearer "+token);
        if(body!=null) request.contentType("application/json").content(json.writeValueAsString(body));
        return mvc.perform(request).andReturn();
    }
    private JsonNode call(String method,String path,String token,Object body,int expected) throws Exception {
        MvcResult result=raw(method,path,token,body);
        assertEquals(expected,result.getResponse().getStatus(),method+" "+path);
        JsonNode response=json.readTree(result.getResponse().getContentAsString());
        assertEquals(expected,response.path("code").asInt()); return response.path("data");
    }
}
