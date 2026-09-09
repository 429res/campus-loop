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
class PlatformCompletionIntegrationTest {
    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry registry) {
        boolean mysql = Boolean.getBoolean("campus.mysql-test");
        String url = mysql ? System.getenv("TEST_DB_URL") :
            "jdbc:h2:mem:campus_loop_completion_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
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


    @Autowired edu.campusloop.web.admin.service.OperationsService operations;
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired PlatformTransactionManager transactions;
    @Autowired edu.campusloop.web.notification.service.NotificationService notifications;
    private final List<Long> ids=new ArrayList<>();private Account admin,owner,other;
    private record Account(long id,String token,String username,String password){}
    @BeforeEach void setup() throws Exception {admin=account(true);owner=account(false);other=account(false);}
    @AfterEach void cleanup(){
        for(long id:ids){jdbc.update("DELETE FROM cl_user_access_audit WHERE target_user_id=? OR actor_user_id=?",id,id);jdbc.update("DELETE FROM cl_user_status_audit WHERE target_user_id=? OR operator_user_id=?",id,id);}
        for(long id:ids){jdbc.update("DELETE FROM cl_notification WHERE user_id=?",id);jdbc.update("DELETE FROM cl_auth_session WHERE user_id=?",id);jdbc.update("DELETE FROM cl_user WHERE id=?",id);}
    }
    @Test void profileWritesArePrivateVersionedAndCannotChangeAccess() throws Exception {
        var body=new LinkedHashMap<String,Object>(Map.of("version",0,"displayName","新名称","bio","喜欢阅读","campus","北校区","contact","仅本人可见","avatarUrl",""));
        var saved=call("PUT","/api/account/profile",owner.token(),body,200);
        assertEquals(1,saved.path("version").asInt());assertEquals("仅本人可见",call("GET","/api/account/profile",owner.token(),null,200).path("contact").asText());
        call("PUT","/api/account/profile",owner.token(),body,409);body.put("version",1);body.put("role","ADMIN");call("PUT","/api/account/profile",owner.token(),body,400);
        body.remove("role");body.put("avatarUrl","https://unknown.invalid/a.png");call("PUT","/api/account/profile",owner.token(),body,400);
        assertEquals("USER",jdbc.queryForObject("SELECT role FROM cl_user WHERE id=?",String.class,owner.id()));
        var listing=call("GET","/api/admin/users",admin.token(),null,200);for(var row:listing.path("records")){assertFalse(row.has("contact"));assertFalse(row.has("passwordHash"));}
    }
    @Test void accessAssignmentRevokesSessionsAndEnforcesEachModule() throws Exception {
        call("PATCH","/api/admin/users/"+owner.id()+"/access",owner.token(),Map.of("version",0,"role","ADMIN","permissions",List.of("ALL"),"reason","无权提升"),403);
        call("PATCH","/api/admin/users/"+owner.id()+"/access",admin.token(),Map.of("version",0,"role","ADMIN","permissions",List.of("ITEMS"),"reason","负责物品审核"),200);
        call("GET","/api/auth/me",owner.token(),null,401);String token=login(owner);
        call("GET","/api/admin/items",token,null,200);call("GET","/api/admin/users",token,null,403);call("GET","/api/admin/operations/summary",token,null,403);
        call("PATCH","/api/admin/users/"+other.id()+"/access",token,Map.of("version",0,"role","ADMIN","permissions",List.of("ALL"),"reason","不可转授权"),403);
        call("PATCH","/api/admin/users/"+admin.id()+"/access",admin.token(),Map.of("version",0,"role","USER","permissions",List.of(),"reason","不能自降权"),409);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_user_access_audit WHERE target_user_id=?",Integer.class,owner.id()));
        assertEquals(1,call("GET","/api/notifications/unread-count",token,null,200).asInt());
    }
    @Test void notificationsAreScopedIdempotentAndTransactional() throws Exception {
        var tx=new TransactionTemplate(transactions);
        tx.executeWithoutResult(s->notifications.send(owner.id(),"EXCHANGE","交换邀请","有同学发来邀请","/pages/exchanges/exchanges","fixture-a"));
        long id=call("GET","/api/notifications",owner.token(),null,200).at("/records/0/id").asLong();
        call("PATCH","/api/notifications/"+id+"/read",other.token(),Map.of(),404);
        assertEquals(0,call("GET","/api/notifications",other.token(),null,200).path("total").asInt());
        call("PATCH","/api/notifications/"+id+"/read",owner.token(),Map.of(),200);call("PATCH","/api/notifications/"+id+"/read",owner.token(),Map.of(),200);
        assertEquals(0,call("GET","/api/notifications/unread-count",owner.token(),null,200).asInt());
        tx.executeWithoutResult(s->{notifications.send(owner.id(),"EXCHANGE","回滚","不会留下","/pages/exchanges/exchanges","fixture-rollback");s.setRollbackOnly();});
        assertEquals(1,call("GET","/api/notifications",owner.token(),null,200).path("total").asInt());
        call("POST","/api/notifications/read-all",owner.token(),Map.of(),200);
    }
    @Test void operationsReflectDatabaseCountsAndKeepAuditPermissions() throws Exception {
        operations.summary(7);
        var data=call("GET","/api/admin/operations/summary?days=7",admin.token(),null,200);
        assertEquals(7,data.path("trend").size());assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM cl_user",Long.class),data.at("/totals/users").asLong());
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM cl_item",Long.class),data.at("/totals/items").asLong());
        call("GET","/api/admin/operations/summary?days=8",admin.token(),null,400);call("GET","/api/admin/operations/audits",owner.token(),null,403);
        call("PATCH","/api/admin/users/"+owner.id()+"/access",admin.token(),Map.of("version",0,"role","ADMIN","permissions",List.of("OPERATIONS"),"reason","统计工作"),200);
        var logs=call("GET","/api/admin/operations/audits?module=USERS",admin.token(),null,200);assertTrue(logs.path("total").asLong()>=1);
        assertTrue(logs.path("records").findValuesAsText("action").contains("ACCESS_CHANGED"));
    }
    private Account account(boolean administrator)throws Exception{
        String name="completion_"+UUID.randomUUID(),password=UUID.randomUUID().toString();long id=call("POST","/api/auth/register",null,Map.of("username",name,"password",password,"displayName","功能验证"),200).path("id").asLong();ids.add(id);
        if(administrator)jdbc.update("UPDATE cl_user SET role='ADMIN',admin_permissions='ALL' WHERE id=?",id);
        String token=call("POST","/api/auth/login",null,Map.of("username",name,"password",password),200).path("token").asText();return new Account(id,token,name,password);
    }
    private String login(Account a)throws Exception{return call("POST","/api/auth/login",null,Map.of("username",a.username(),"password",a.password()),200).path("token").asText();}
    private JsonNode call(String method,String path,String token,Object body,int expected)throws Exception{
        var req=request(HttpMethod.valueOf(method),path);if(token!=null)req.header("Authorization","Bearer "+token);if(body!=null)req.contentType("application/json").content(json.writeValueAsString(body));
        var response=mvc.perform(req).andReturn().getResponse();assertEquals(expected,response.getStatus(),method+" "+path+" "+response.getContentAsString());return json.readTree(response.getContentAsString()).path("data");
    }
}
