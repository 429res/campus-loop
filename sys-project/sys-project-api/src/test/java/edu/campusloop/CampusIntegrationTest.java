package edu.campusloop;
import edu.campusloop.auth.PasswordService;
import edu.campusloop.common.ApiException;
import edu.campusloop.matching.IndependentMatchingInput;
import edu.campusloop.web.matching.service.IndependentMatchingSnapshotService;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import javax.sql.DataSource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles(resolver=CampusIntegrationTest.Profile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CampusIntegrationTest {
    public static class Profile implements ActiveProfilesResolver {
        @Override public String[] resolve(Class<?> testClass) {
            return new String[]{Boolean.getBoolean("campus.mysql-test") ? "mysql-test" : "test"};
        }
    }
    private static final Path UPLOADS=tempUploads();
    private static Path tempUploads(){try{return Files.createTempDirectory("campus-loop-test-uploads-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry){
        if(Boolean.getBoolean("campus.mysql-test")) {
            String url=System.getenv("TEST_DB_URL");
            if(url==null || !url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/campus_loop_[a-z0-9_]*test(?:\\?.*)?"))
                throw new IllegalStateException("MySQL tests require an explicitly isolated localhost campus_loop_*test schema");
        }
        boolean mysql=Boolean.getBoolean("campus.mysql-test");
        String url=mysql?System.getenv("TEST_DB_URL"):"jdbc:h2:mem:campus_loop_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        String username=mysql?System.getenv("TEST_DB_USERNAME"):"sa";
        String password=mysql?System.getenv("TEST_DB_PASSWORD"):"";
        if(username==null || password==null) throw new IllegalStateException("Explicit test database credentials are required");
        // Dynamic properties outrank ambient SPRING_* variables, including a separately configured Flyway URL.
        registry.add("spring.datasource.url",()->url);
        registry.add("spring.datasource.username",()->username);
        registry.add("spring.datasource.password",()->password);
        registry.add("spring.datasource.driver-class-name",()->mysql?"com.mysql.cj.jdbc.Driver":"org.h2.Driver");
        registry.add("spring.flyway.url",()->url);
        registry.add("spring.flyway.user",()->username);
        registry.add("spring.flyway.password",()->password);
        registry.add("campus.bootstrap-enabled",()->false);
        registry.add("campus.registration-mode",()->"DEVELOPMENT_SELF_SERVICE");
        registry.add("campus.jwt-secret",()->UUID.randomUUID().toString()+UUID.randomUUID());
        registry.add("campus.upload-dir",()->UPLOADS.toString());
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired UserMapper users;
    @Autowired PasswordService passwords;@Autowired JdbcTemplate jdbc;@Autowired DataSource ds;
    @Autowired IndependentMatchingSnapshotService independentSnapshots;
    @Autowired PlatformTransactionManager transactions;
    String memberToken,adminToken; long memberId,adminId; String memberName,memberPassword,adminName;
    @BeforeAll void setup() throws Exception {
        new ResourceDatabasePopulator(new ClassPathResource("db/demo-data.sql")).execute(ds);
        memberName="test_"+UUID.randomUUID().toString().substring(0,12);memberPassword=UUID.randomUUID().toString();
        memberId=account(memberName,memberPassword,"USER");
        adminName="test_"+UUID.randomUUID().toString().substring(0,12);String adminPassword=UUID.randomUUID().toString();
        adminId=account(adminName,adminPassword,"ADMIN");memberToken=login(memberName,memberPassword);adminToken=login(adminName,adminPassword);
    }
    long account(String username,String password,String role){
        User user=new User();user.setUsername(username);user.setPasswordHash(passwords.encode(password));user.setDisplayName("集成测试同学");
        user.setRole(role);user.setStatus("ACTIVE");user.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));users.insert(user);return user.getId();
    }
    String login(String username,String password) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username",username,"password",password))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.user.passwordHash").doesNotExist()).andReturn().getResponse().getContentAsString()).at("/data/token").asText();
    }
    @Test void realPublishPersistsAndAdminCanQuery() throws Exception {
        String title="独立数据库验证 "+UUID.randomUUID();
        String body=json.writeValueAsString(Map.of("title",title,"description","数据库持久化验证物品","categoryId",1,"conditionLevel",4,"tags",List.of("教材"),"wantedCategoryId",2,"wantedTags",List.of("便携")));
        JsonNode created=json.readTree(mvc.perform(post("/api/items").header("Authorization","Bearer "+memberToken).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_REVIEW")).andReturn().getResponse().getContentAsString());
        long id=created.at("/data/id").asLong();
        assertEquals(title,jdbc.queryForObject("SELECT title FROM cl_item WHERE id=?",String.class,id));
        mvc.perform(get("/api/items/"+id)).andExpect(status().isNotFound());
        demandCall("POST","/api/admin/items/"+id+"/review",adminToken,Map.of("version",0,"decision","APPROVE","reason","内容完整"),200);
        mvc.perform(get("/api/items/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value(title));
        mvc.perform(get("/api/items").param("keyword",title)).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/admin/items").param("keyword",title).header("Authorization","Bearer "+adminToken)).andExpect(jsonPath("$.data.records[0].id").value(id));
    }
    @Test void protectsRoutesAndRejectsInvalidInput() throws Exception {
        mvc.perform(post("/api/items").contentType("application/json").content("{}" )).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        mvc.perform(get("/api/admin/items").header("Authorization","Bearer "+memberToken)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        mvc.perform(post("/api/items").header("Authorization","Bearer "+memberToken).contentType("application/json").content("{}" )).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/items").param("size","101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/items/999999999")).andExpect(status().isNotFound());
        mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username","demo_leaf","password",UUID.randomUUID().toString())))).andExpect(status().isUnauthorized());
    }
    @Test void adminUserQueryIsProtectedFilteredAndSafe() throws Exception {
        String username="filter_"+UUID.randomUUID().toString().substring(0,10);
        long userId=account(username,UUID.randomUUID().toString(),"USER");
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        mvc.perform(get("/api/admin/users").header("Authorization","Bearer "+memberToken))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        mvc.perform(get("/api/admin/users").header("Authorization","Bearer "+adminToken).param("keyword",username)
            .param("role","USER").param("status","ACTIVE").param("page","1").param("size","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].id").value(userId)).andExpect(jsonPath("$.data.records[0].username").value(username))
            .andExpect(jsonPath("$.data.records[0].role").value("USER")).andExpect(jsonPath("$.data.records[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.records[0].version").value(0)).andExpect(jsonPath("$.data.records[0].passwordHash").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].token").doesNotExist());
        mvc.perform(get("/api/admin/users").header("Authorization","Bearer "+adminToken).param("role","OWNER"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/users").header("Authorization","Bearer "+adminToken).param("status","LOCKED"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/users").header("Authorization","Bearer "+adminToken).param("size","101"))
            .andExpect(status().isBadRequest());
    }
    @Test void adminDisableRevokesSessionsAuditsAndRequiresFreshLoginAfterEnable() throws Exception {
        String username="status_"+UUID.randomUUID().toString().substring(0,10),password=UUID.randomUUID().toString();
        long userId=account(username,password,"USER");String first=login(username,password),second=login(username,password);
        String itemTitle="停用保留历史物品 "+UUID.randomUUID();
        String itemBody=json.writeValueAsString(Map.of("title",itemTitle,"description","状态测试物品","categoryId",1,"conditionLevel",4,
            "tags",List.of("测试"),"wantedCategoryId",2,"wantedTags",List.of()));
        long itemId=json.readTree(mvc.perform(post("/api/items").header("Authorization","Bearer "+first).contentType("application/json").content(itemBody))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data/id").asLong();

        String disable=json.writeValueAsString(Map.of("status","DISABLED","version",0,"reason","  账号状态验收  "));
        mvc.perform(patch("/api/admin/users/"+userId+"/status").header("Authorization","Bearer "+adminToken)
            .contentType("application/json").content(json.writeValueAsString(Map.of("status","DISABLED","version",0,
                "reason","不应执行","role","ADMIN","passwordHash","not-a-hash"))))
            .andExpect(status().isBadRequest());
        assertEquals("ACTIVE",users.selectById(userId).getStatus());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM cl_auth_session WHERE user_id=?",Integer.class,userId));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_user_status_audit WHERE target_user_id=?",Integer.class,userId));
        mvc.perform(patch("/api/admin/users/"+userId+"/status").header("Authorization","Bearer "+memberToken)
            .contentType("application/json").content(disable)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/users/"+userId+"/status").header("Authorization","Bearer "+adminToken)
            .contentType("application/json").content(disable)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("DISABLED")).andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_auth_session WHERE user_id=?",Integer.class,userId));
        assertEquals("DISABLED",jdbc.queryForObject("SELECT status FROM cl_user WHERE id=?",String.class,userId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_user_status_audit WHERE target_user_id=?",Integer.class,userId));
        assertEquals("账号状态验收",jdbc.queryForObject("SELECT reason FROM cl_user_status_audit WHERE target_user_id=?",String.class,userId));
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+second)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username",username,"password",password))))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/items/"+itemId)).andExpect(status().isNotFound()); // New submission remains private even after account disable.
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item WHERE id=?",Integer.class,itemId));

        String enable=json.writeValueAsString(Map.of("status","ACTIVE","version",1,"reason","确认恢复使用"));
        mvc.perform(patch("/api/admin/users/"+userId+"/status").header("Authorization","Bearer "+adminToken)
            .contentType("application/json").content(enable)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ACTIVE")).andExpect(jsonPath("$.data.version").value(2));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_auth_session WHERE user_id=?",Integer.class,userId));
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+first)).andExpect(status().isUnauthorized());
        assertFalse(login(username,password).isBlank());
        mvc.perform(get("/api/admin/users/"+userId+"/status-audits").header("Authorization","Bearer "+adminToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.records[0].operatorUsername").value(adminName))
            .andExpect(jsonPath("$.data.records[0].reason").value("确认恢复使用"))
            .andExpect(jsonPath("$.data.records[0].passwordHash").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].token").doesNotExist());
    }
    @Test void staleAndConcurrentStatusWritesDoNotOverwrite() throws Exception {
        String username="conflict_"+UUID.randomUUID().toString().substring(0,8);
        long userId=account(username,UUID.randomUUID().toString(),"USER");
        String secondAdminName="admin_"+UUID.randomUUID().toString().substring(0,8),secondAdminPassword=UUID.randomUUID().toString();
        account(secondAdminName,secondAdminPassword,"ADMIN");String secondAdminToken=login(secondAdminName,secondAdminPassword);
        String body=json.writeValueAsString(Map.of("status","DISABLED","version",0,"reason","并发停用测试"));
        ExecutorService executor=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try {
            Future<MvcResult> first=executor.submit(()->{ready.countDown();start.await();return mvc.perform(patch("/api/admin/users/"+userId+"/status")
                .header("Authorization","Bearer "+adminToken).contentType("application/json").content(body)).andReturn();});
            Future<MvcResult> second=executor.submit(()->{ready.countDown();start.await();return mvc.perform(patch("/api/admin/users/"+userId+"/status")
                .header("Authorization","Bearer "+secondAdminToken).contentType("application/json").content(body)).andReturn();});
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            Set<Integer> statuses=Set.of(first.get(30,TimeUnit.SECONDS).getResponse().getStatus(),second.get(30,TimeUnit.SECONDS).getResponse().getStatus());
            assertEquals(Set.of(200,409),statuses);
        } finally {start.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
        assertEquals("DISABLED",users.selectById(userId).getStatus());assertEquals(1,users.selectById(userId).getVersion());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_user_status_audit WHERE target_user_id=?",Integer.class,userId));
        mvc.perform(patch("/api/admin/users/"+userId+"/status").header("Authorization","Bearer "+adminToken)
            .contentType("application/json").content(body)).andExpect(status().isConflict());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_user_status_audit WHERE target_user_id=?",Integer.class,userId));
    }
    @Test void concurrentLoginCannotSurviveDisableAndAdminCannotDisableSelf() throws Exception {
        String username="race_"+UUID.randomUUID().toString().substring(0,10),password=UUID.randomUUID().toString();
        long userId=account(username,password,"USER");String body=json.writeValueAsString(Map.of("status","DISABLED","version",0,"reason","并发登录停用测试"));
        ExecutorService executor=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try {
            Future<MvcResult> loginResult=executor.submit(()->{ready.countDown();start.await();return mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username",username,"password",password)))).andReturn();});
            Future<MvcResult> disableResult=executor.submit(()->{ready.countDown();start.await();return mvc.perform(patch("/api/admin/users/"+userId+"/status")
                .header("Authorization","Bearer "+adminToken).contentType("application/json").content(body)).andReturn();});
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            MvcResult loginResponse=loginResult.get(30,TimeUnit.SECONDS),disableResponse=disableResult.get(30,TimeUnit.SECONDS);
            assertEquals(200,disableResponse.getResponse().getStatus());assertTrue(Set.of(200,401).contains(loginResponse.getResponse().getStatus()));
            if(loginResponse.getResponse().getStatus()==200) {
                String racedToken=json.readTree(loginResponse.getResponse().getContentAsString()).at("/data/token").asText();
                mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+racedToken)).andExpect(status().isUnauthorized());
            }
        } finally {start.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_auth_session WHERE user_id=?",Integer.class,userId));
        mvc.perform(patch("/api/admin/users/"+adminId+"/status").header("Authorization","Bearer "+adminToken).contentType("application/json")
            .content(json.writeValueAsString(Map.of("status","DISABLED","version",users.selectById(adminId).getVersion(),"reason","不允许自我停用"))))
            .andExpect(status().isConflict());
        assertEquals("ACTIVE",users.selectById(adminId).getStatus());
    }
    @Test void concurrentAdminsCannotDisableEachOtherAndRemoveEveryEntry() throws Exception {
        String firstName="guard_"+UUID.randomUUID().toString().substring(0,8),firstPassword=UUID.randomUUID().toString();
        String secondName="guard_"+UUID.randomUUID().toString().substring(0,8),secondPassword=UUID.randomUUID().toString();
        long firstId=account(firstName,firstPassword,"ADMIN"),secondId=account(secondName,secondPassword,"ADMIN");
        String firstToken=login(firstName,firstPassword),secondToken=login(secondName,secondPassword);
        String disableFirst=json.writeValueAsString(Map.of("status","DISABLED","version",0,"reason","交叉停用保护"));
        String disableSecond=json.writeValueAsString(Map.of("status","DISABLED","version",0,"reason","交叉停用保护"));
        ExecutorService executor=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try {
            Future<MvcResult> byFirst=executor.submit(()->{ready.countDown();start.await();return mvc.perform(patch("/api/admin/users/"+secondId+"/status")
                .header("Authorization","Bearer "+firstToken).contentType("application/json").content(disableSecond)).andReturn();});
            Future<MvcResult> bySecond=executor.submit(()->{ready.countDown();start.await();return mvc.perform(patch("/api/admin/users/"+firstId+"/status")
                .header("Authorization","Bearer "+secondToken).contentType("application/json").content(disableFirst)).andReturn();});
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            int firstStatus=byFirst.get(30,TimeUnit.SECONDS).getResponse().getStatus();
            int secondStatus=bySecond.get(30,TimeUnit.SECONDS).getResponse().getStatus();
            assertEquals(1,List.of(firstStatus,secondStatus).stream().filter(code->code==200).count());
            assertEquals(1,List.of(firstStatus,secondStatus).stream().filter(code->code==401).count());
        } finally {start.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
        assertEquals(1,List.of(users.selectById(firstId),users.selectById(secondId)).stream().filter(user->"ACTIVE".equals(user.getStatus())).count());
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM cl_user WHERE role='ADMIN' AND status='ACTIVE' AND password_hash IS NOT NULL",Integer.class)>0);
    }
    @Test void developmentRegistrationPersistsSafeUserAndUsesExistingLogin() throws Exception {
        String username="reg_"+UUID.randomUUID().toString().substring(0,12),password="Valid-"+UUID.randomUUID();
        String displayName="注册同学 "+UUID.randomUUID().toString().substring(0,6);
        JsonNode response=json.readTree(mvc.perform(post("/api/auth/register").contentType("application/json")
            .content(json.writeValueAsString(Map.of("username","  "+username+"  ","password",password,"displayName","  "+displayName+"  "))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value(username))
            .andExpect(jsonPath("$.data.displayName").value(displayName)).andExpect(jsonPath("$.data.role").value("USER"))
            .andExpect(jsonPath("$.data.status").doesNotExist()).andExpect(jsonPath("$.data.passwordHash").doesNotExist())
            .andExpect(jsonPath("$.data.token").doesNotExist()).andReturn().getResponse().getContentAsString());
        long userId=response.at("/data/id").asLong();User stored=users.selectById(userId);
        assertEquals("USER",stored.getRole());assertEquals("ACTIVE",stored.getStatus());assertNotEquals(password,stored.getPasswordHash());
        assertTrue(stored.getPasswordHash().startsWith("$2"));assertTrue(passwords.matches(password,stored.getPasswordHash()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_auth_session WHERE user_id=?",Integer.class,userId));
        String token=login(username,password);
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(userId));
    }
    @Test void registrationRejectsInvalidAndProtectedFieldsWithoutPartialUser() throws Exception {
        String username="invalid_"+UUID.randomUUID().toString().substring(0,10),password="Valid-"+UUID.randomUUID();
        mvc.perform(post("/api/auth/register").contentType("application/json")
            .content(json.writeValueAsString(Map.of("username","bad name","password",password,"displayName","同学"))))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").contentType("application/json")
            .content(json.writeValueAsString(Map.of("username",username,"password","too-short","displayName","同学"))))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").contentType("application/json")
            .content(json.writeValueAsString(Map.of("username",username,"password","密".repeat(25),"displayName","同学"))))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").contentType("application/json")
            .content(json.writeValueAsString(Map.of("username",username,"password",password,"displayName","   "))))
            .andExpect(status().isBadRequest());
        Map<String,Object> protectedFields=new LinkedHashMap<>(Map.of("username",username,"password",password,"displayName","不应注册"));
        protectedFields.put("role","ADMIN");protectedFields.put("status","ACTIVE");protectedFields.put("passwordHash","not-a-hash");
        mvc.perform(post("/api/auth/register").contentType("application/json").content(json.writeValueAsString(protectedFields)))
            .andExpect(status().isBadRequest());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_user WHERE username=?",Integer.class,username));
    }
    @Test void duplicateAndConcurrentRegistrationKeepExactlyOneUser() throws Exception {
        String sequentialName="dup_"+UUID.randomUUID().toString().substring(0,10),password="Valid-"+UUID.randomUUID();
        String sequentialBody=json.writeValueAsString(Map.of("username",sequentialName,"password",password,"displayName","重复注册测试"));
        mvc.perform(post("/api/auth/register").contentType("application/json").content(sequentialBody)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/register").contentType("application/json").content(sequentialBody))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.msg").value("用户名已存在"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_user WHERE username=?",Integer.class,sequentialName));

        String concurrentName="race_"+UUID.randomUUID().toString().substring(0,9);
        String concurrentBody=json.writeValueAsString(Map.of("username",concurrentName,"password",password,"displayName","并发注册测试"));
        ExecutorService executor=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try {
            Future<MvcResult> first=executor.submit(()->{ready.countDown();start.await();return mvc.perform(post("/api/auth/register")
                .contentType("application/json").content(concurrentBody)).andReturn();});
            Future<MvcResult> second=executor.submit(()->{ready.countDown();start.await();return mvc.perform(post("/api/auth/register")
                .contentType("application/json").content(concurrentBody)).andReturn();});
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            int firstStatus=first.get(30,TimeUnit.SECONDS).getResponse().getStatus();
            int secondStatus=second.get(30,TimeUnit.SECONDS).getResponse().getStatus();
            assertEquals(1,List.of(firstStatus,secondStatus).stream().filter(code->code==200).count());
            assertEquals(1,List.of(firstStatus,secondStatus).stream().filter(code->code==409).count());
        } finally {start.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_user WHERE username=?",Integer.class,concurrentName));
        User stored=users.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<User>().eq("username",concurrentName));
        assertEquals("USER",stored.getRole());assertEquals("ACTIVE",stored.getStatus());assertTrue(passwords.matches(password,stored.getPasswordHash()));
    }
    @Test void logoutActuallyRevokesAndExpiredSessionIsRejected() throws Exception {
        String token=login(memberName,memberPassword);
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(memberId));
        mvc.perform(post("/api/auth/logout").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        String expiryName="test_"+UUID.randomUUID().toString().substring(0,12), expiryPassword=UUID.randomUUID().toString();
        long expiryId=account(expiryName,expiryPassword,"USER");String expiring=login(expiryName,expiryPassword);
        jdbc.update("UPDATE cl_auth_session SET expires_at=? WHERE user_id=?",LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1),expiryId);
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+expiring)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
    }
    @Test void profileUpdatePersistsAndRejectsProtectedFields() throws Exception {
        String username="test_"+UUID.randomUUID().toString().substring(0,12), password=UUID.randomUUID().toString();
        long userId=account(username,password,"USER");String token=login(username,password);
        User original=users.selectById(userId);String originalHash=original.getPasswordHash();
        mvc.perform(patch("/api/auth/me").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        Map<String,Object> protectedFields=new LinkedHashMap<>();
        protectedFields.put("displayName","不应保存");protectedFields.put("id",999999);protectedFields.put("username","attacker");
        protectedFields.put("role","ADMIN");protectedFields.put("status","DISABLED");protectedFields.put("passwordHash","not-a-hash");
        mvc.perform(patch("/api/auth/me").header("Authorization","Bearer "+token).contentType("application/json")
            .content(json.writeValueAsString(protectedFields))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        User unchanged=users.selectById(userId);
        assertEquals("集成测试同学",unchanged.getDisplayName());assertEquals(username,unchanged.getUsername());assertEquals("USER",unchanged.getRole());
        assertEquals("ACTIVE",unchanged.getStatus());assertEquals(originalHash,unchanged.getPasswordHash());
        String displayName="资料持久化 "+UUID.randomUUID().toString().substring(0,8);
        mvc.perform(patch("/api/auth/me").header("Authorization","Bearer "+token).contentType("application/json")
            .content(json.writeValueAsString(Map.of("displayName","  "+displayName+"  "))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(userId))
            .andExpect(jsonPath("$.data.username").value(username)).andExpect(jsonPath("$.data.displayName").value(displayName))
            .andExpect(jsonPath("$.data.role").value("USER")).andExpect(jsonPath("$.data.status").doesNotExist())
            .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.displayName").value(displayName));
        assertEquals(displayName,jdbc.queryForObject("SELECT display_name FROM cl_user WHERE id=?",String.class,userId));
        User updated=users.selectById(userId);assertEquals(originalHash,updated.getPasswordHash());assertEquals("USER",updated.getRole());
    }
    @Test void passwordChangeRejectsWrongCurrentPasswordAndRevokesEverySession() throws Exception {
        String username="test_"+UUID.randomUUID().toString().substring(0,12), oldPassword=UUID.randomUUID().toString(), newPassword=UUID.randomUUID().toString();
        long userId=account(username,oldPassword,"USER");String first=login(username,oldPassword),second=login(username,oldPassword);
        String originalHash=users.selectById(userId).getPasswordHash();
        mvc.perform(post("/api/auth/password").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        mvc.perform(post("/api/auth/password").header("Authorization","Bearer "+first).contentType("application/json")
            .content(json.writeValueAsString(Map.of("currentPassword","wrong-"+UUID.randomUUID(),"newPassword",newPassword))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        assertEquals(originalHash,users.selectById(userId).getPasswordHash());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM cl_auth_session WHERE user_id=?",Integer.class,userId));
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+first)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+second)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/password").header("Authorization","Bearer "+first).contentType("application/json")
            .content(json.writeValueAsString(Map.of("currentPassword",oldPassword,"newPassword",newPassword))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());
        assertNotEquals(originalHash,users.selectById(userId).getPasswordHash());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_auth_session WHERE user_id=?",Integer.class,userId));
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+second)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username",username,"password",oldPassword))))
            .andExpect(status().isUnauthorized());
        String replacement=login(username,newPassword);
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+replacement)).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(userId));
    }
    @Test void concurrentOldPasswordLoginCannotSurvivePasswordChange() throws Exception {
        String username="test_"+UUID.randomUUID().toString().substring(0,12), oldPassword=UUID.randomUUID().toString(), newPassword=UUID.randomUUID().toString();
        long userId=account(username,oldPassword,"USER");String changeToken=login(username,oldPassword);
        ExecutorService executor=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try {
            Future<MvcResult> oldLogin=executor.submit(()->{ready.countDown();start.await();return mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username",username,"password",oldPassword)))).andReturn();});
            Future<MvcResult> passwordChange=executor.submit(()->{ready.countDown();start.await();return mvc.perform(post("/api/auth/password")
                .header("Authorization","Bearer "+changeToken).contentType("application/json")
                .content(json.writeValueAsString(Map.of("currentPassword",oldPassword,"newPassword",newPassword)))).andReturn();});
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            MvcResult loginResult=oldLogin.get(30,TimeUnit.SECONDS),changeResult=passwordChange.get(30,TimeUnit.SECONDS);
            assertEquals(200,changeResult.getResponse().getStatus());
            assertTrue(Set.of(200,401).contains(loginResult.getResponse().getStatus()));
            if(loginResult.getResponse().getStatus()==200) {
                String racedToken=json.readTree(loginResult.getResponse().getContentAsString()).at("/data/token").asText();
                mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+racedToken)).andExpect(status().isUnauthorized());
            }
        } finally {start.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_auth_session WHERE user_id=?",Integer.class,userId));
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+changeToken)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username",username,"password",oldPassword))))
            .andExpect(status().isUnauthorized());
        assertFalse(login(username,newPassword).isBlank());
    }
    @Test void recommendationsAreReadOnlyAndFixturesAreRepeatable() throws Exception {
        long items=jdbc.queryForObject("SELECT COUNT(*) FROM cl_item",Long.class),users=jdbc.queryForObject("SELECT COUNT(*) FROM cl_user",Long.class);
        new ResourceDatabasePopulator(new ClassPathResource("db/demo-data.sql")).execute(ds);
        assertEquals(items,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item",Long.class));assertEquals(users,jdbc.queryForObject("SELECT COUNT(*) FROM cl_user",Long.class));
        JsonNode matches=json.readTree(mvc.perform(get("/api/matches")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        boolean two=false,three=false;for(JsonNode match:matches){two|=match.get("length").asInt()==2;three|=match.get("length").asInt()==3;}
        assertTrue(two);assertTrue(three);
        jdbc.update("UPDATE cl_user SET status='DISABLED' WHERE id=1001");
        try {
            JsonNode activeOnly=json.readTree(mvc.perform(get("/api/matches")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
            for(JsonNode match:activeOnly) for(JsonNode participant:match.get("participants")) assertNotEquals(1001,participant.get("userId").asLong());
        } finally {jdbc.update("UPDATE cl_user SET status='ACTIVE' WHERE id=1001");}
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange",Integer.class));
        mvc.perform(post("/api/exchanges").contentType("application/json").content("{\"ruleVersion\":\"independent-v2\",\"idempotencyKey\":\"pending-test\",\"flows\":[{\"itemId\":1,\"itemVersion\":0,\"demandId\":2,\"demandVersion\":0},{\"itemId\":2,\"itemVersion\":0,\"demandId\":1,\"demandVersion\":0}]}").header("Authorization","Bearer "+memberToken)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
    }
    @Test void uploadValidatesContentAndOwnership() throws Exception {
        mvc.perform(multipart("/api/uploads").file(new MockMultipartFile("file","fake.png","image/png","not an image".getBytes())).header("Authorization","Bearer "+memberToken)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/uploads").file(new MockMultipartFile("file","a.svg","image/svg+xml","<svg/>".getBytes())).header("Authorization","Bearer "+memberToken)).andExpect(status().isBadRequest());
        ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",out);
        JsonNode response=json.readTree(mvc.perform(multipart("/api/uploads").file(new MockMultipartFile("file","../../sample.png","image/png",out.toByteArray())).header("Authorization","Bearer "+memberToken))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String url=response.at("/data/url").asText();assertTrue(Files.exists(UPLOADS.resolve(url.substring("/uploads/".length()))));
        Map<String,Object> item=new HashMap<>(Map.of("title","带图片的测试物品","description","图片持久化","categoryId",1,"conditionLevel",4,"tags",List.of(),"wantedCategoryId",2,"wantedTags",List.of(),"imageUrl",url));
        mvc.perform(post("/api/items").header("Authorization","Bearer "+memberToken).contentType("application/json").content(json.writeValueAsString(item))).andExpect(status().isOk());
        mvc.perform(post("/api/items").header("Authorization","Bearer "+adminToken).contentType("application/json").content(json.writeValueAsString(item))).andExpect(status().isBadRequest());
        item.put("imageUrl","https://untrusted.invalid/x.png");
        mvc.perform(post("/api/items").header("Authorization","Bearer "+memberToken).contentType("application/json").content(json.writeValueAsString(item))).andExpect(status().isBadRequest());
    }
    @Test void healthCategoriesAndAdminStatsReadDatabase() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.data.database").value("UP"));
        mvc.perform(get("/api/categories")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(6));
        mvc.perform(get("/api/admin/stats").header("Authorization","Bearer "+adminToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isNumber());
    }

    @Test void independentDemandPersistsWithoutItemsAcrossSessionsAndSupportsPagingAndStatus() throws Exception {
        String username="test_"+UUID.randomUUID().toString().substring(0,12), password=UUID.randomUUID().toString();
        long ownerId=account(username,password,"USER");String token=login(username,password);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item WHERE owner_id=?",Integer.class,ownerId));
        JsonNode first=demandCall("POST","/api/demands",token,demandBody(2,"无物品也可保存",List.of(" USB ","usb","便携"),List.of()),200);
        long firstId=first.path("id").asLong();
        assertEquals(ownerId,first.path("ownerId").asLong());assertEquals("ACTIVE",first.path("status").asText());
        assertEquals(0,first.path("version").asInt());assertEquals(json.valueToTree(List.of("usb","便携")),first.path("preferredTags"));
        assertEquals(0,first.path("offeredItems").size());assertFalse(first.path("createdAt").asText().isBlank());assertFalse(first.path("updatedAt").asText().isBlank());
        assertEquals("无物品也可保存",jdbc.queryForObject("SELECT description FROM cl_demand WHERE id=?",String.class,firstId));
        assertEquals(ownerId,jdbc.queryForObject("SELECT owner_id FROM cl_demand WHERE id=?",Long.class,firstId));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item WHERE owner_id=?",Integer.class,ownerId));
        demandCall("POST","/api/auth/logout",token,null,200);
        String newSession=login(username,password);
        assertEquals(first,demandCall("GET","/api/demands/"+firstId,newSession,null,200));
        JsonNode second=demandCall("POST","/api/demands",newSession,demandBody(1,"",List.of(),List.of()),200);
        JsonNode page=demandCall("GET","/api/demands?page=1&size=1",newSession,null,200);
        assertEquals(2,page.path("total").asInt());assertEquals(1,page.path("page").asInt());assertEquals(1,page.path("size").asInt());
        assertEquals(second.path("id"),page.at("/records/0/id"));
        assertEquals(firstId,demandCall("GET","/api/demands?page=2&size=1",newSession,null,200).at("/records/0/id").asLong());
        assertEquals(0,demandCall("GET","/api/demands?page=3&size=1",newSession,null,200).path("records").size());
        JsonNode edited=demandCall("PATCH","/api/demands/"+firstId,newSession,Map.of("version",0,"description","只改说明"),200);
        assertEquals(1,edited.path("version").asInt());assertEquals(first.path("categoryId"),edited.path("categoryId"));
        assertEquals(first.path("preferredTags"),edited.path("preferredTags"));assertEquals(first.path("createdAt"),edited.path("createdAt"));
        demandCall("PATCH","/api/demands/"+firstId+"/status",newSession,Map.of("version",1,"status","INACTIVE"),200);
        assertEquals(second.path("id"),demandCall("GET","/api/demands?status=ACTIVE",newSession,null,200).at("/records/0/id"));
        JsonNode inactive=demandCall("GET","/api/demands?status=INACTIVE",newSession,null,200);
        assertEquals(1,inactive.path("total").asInt());assertEquals(firstId,inactive.at("/records/0/id").asLong());
        JsonNode sameState=demandCall("PATCH","/api/demands/"+firstId+"/status",newSession,Map.of("version",2,"status","INACTIVE"),200);
        assertEquals(3,sameState.path("version").asInt());
        JsonNode active=demandCall("PATCH","/api/demands/"+firstId+"/status",newSession,Map.of("version",3,"status","ACTIVE"),200);
        assertEquals(4,active.path("version").asInt());assertEquals("ACTIVE",active.path("status").asText());
        assertEquals(2,demandCall("GET","/api/demands?status=ACTIVE",newSession,null,200).path("total").asInt());
    }
    @Test void independentDemandRequiresItsOwnerAndRejectsProtectedOrUnknownFields() throws Exception {
        String owner=demandUser(),other=demandUser();
        Map<String,Object> body=demandBody(1,"本人需求",List.of(),List.of());
        JsonNode created=demandCall("POST","/api/demands",owner,body,200);long id=created.path("id").asLong();
        for(String path:List.of("/api/demands","/api/demands/"+id,"/api/demands/offerable-items")) demandCall("GET",path,null,null,401);
        demandCall("POST","/api/demands",null,body,401);
        demandCall("PATCH","/api/demands/"+id,null,Map.of("version",0,"description","不能修改"),401);
        demandCall("PATCH","/api/demands/"+id+"/status",null,Map.of("version",0,"status","INACTIVE"),401);
        demandCall("DELETE","/api/demands/"+id+"?version=0",null,null,401);
        for(String forbidden:List.of(other,adminToken)) {
            demandCall("GET","/api/demands/"+id,forbidden,null,403);
            demandCall("PATCH","/api/demands/"+id,forbidden,Map.of("version",0,"description","不能修改"),403);
            demandCall("PATCH","/api/demands/"+id+"/status",forbidden,Map.of("version",0,"status","INACTIVE"),403);
            demandCall("DELETE","/api/demands/"+id+"?version=0",forbidden,null,403);
        }
        assertEquals(0,demandCall("GET","/api/demands",other,null,200).path("total").asInt());
        for(String protectedField:List.of("ownerId","owner","id","status","version","createdAt","updatedAt","requiredTags","minimumConditionLevel","unknown")) {
            Map<String,Object> invalid=new LinkedHashMap<>(body);invalid.put(protectedField,1);
            demandCall("POST","/api/demands",owner,invalid,400);
        }
        for(String protectedField:List.of("ownerId","id","status","createdAt","requiredTags","minimumConditionLevel","unknown")) {
            Map<String,Object> invalid=new LinkedHashMap<>(Map.of("version",0,"description","不得部分写入"));invalid.put(protectedField,1);
            demandCall("PATCH","/api/demands/"+id,owner,invalid,400);
        }
        demandCall("PATCH","/api/demands/"+id+"/status",owner,Map.of("version",0,"status","INACTIVE","ownerId",1),400);
        assertEquals(created,demandCall("GET","/api/demands/"+id,owner,null,200));
        assertEquals(1,demandCall("GET","/api/demands",owner,null,200).path("total").asInt());
        demandCall("GET","/api/demands/999999999",owner,null,404);
    }
    @Test void independentDemandValidatesNullPartialFieldsAndRequestBoundaries() throws Exception {
        String token=demandUser();Map<String,Object> body=demandBody(1,"校验基线",List.of("保留"),List.of());
        JsonNode created=demandCall("POST","/api/demands",token,body,200);long id=created.path("id").asLong();
        for(String field:List.of("categoryId","description","preferredTags","offeredItemIds")) {
            Map<String,Object> missing=new LinkedHashMap<>(body);missing.remove(field);demandCall("POST","/api/demands",token,missing,400);
            Map<String,Object> explicitNull=new LinkedHashMap<>(body);explicitNull.put(field,null);demandCall("POST","/api/demands",token,explicitNull,400);
            Map<String,Object> patchNull=new LinkedHashMap<>();patchNull.put("version",0);patchNull.put(field,null);
            demandCall("PATCH","/api/demands/"+id,token,patchNull,400);
        }
        demandCall("PATCH","/api/demands/"+id,token,Map.of("version",0),400);
        demandCall("PATCH","/api/demands/"+id,token,Map.of("description","缺少版本"),400);
        for(Object invalidVersion:List.of(-1,0.5,"0",2147483648L)) {
            demandCall("PATCH","/api/demands/"+id,token,Map.of("version",invalidVersion,"description","非法版本"),400);
            demandCall("PATCH","/api/demands/"+id+"/status",token,Map.of("version",invalidVersion,"status","INACTIVE"),400);
        }
        Map<String,Object> nullVersion=new LinkedHashMap<>();nullVersion.put("version",null);nullVersion.put("description","不能写入");
        demandCall("PATCH","/api/demands/"+id,token,nullVersion,400);
        for(Object invalidCategory:List.of(0,-1,999999999,1.5,"1")) {
            Map<String,Object> invalid=new LinkedHashMap<>(body);invalid.put("categoryId",invalidCategory);demandCall("POST","/api/demands",token,invalid,400);
            demandCall("PATCH","/api/demands/"+id,token,Map.of("version",0,"categoryId",invalidCategory),400);
        }
        for(List<?> invalidTags:List.of(List.of(" "),List.of("x".repeat(21)),Collections.nCopies(9,"标签"),Arrays.asList((String)null),List.of(1))) {
            Map<String,Object> invalid=new LinkedHashMap<>(body);invalid.put("preferredTags",invalidTags);demandCall("POST","/api/demands",token,invalid,400);
        }
        Map<String,Object> tooLong=new LinkedHashMap<>(body);tooLong.put("description","x".repeat(2001));demandCall("POST","/api/demands",token,tooLong,400);
        for(List<?> invalidIds:List.of(List.of(-1),List.of(0),List.of(1.5),List.of("1"),Arrays.asList((Long)null),java.util.stream.LongStream.rangeClosed(1,101).boxed().toList())) {
            Map<String,Object> invalid=new LinkedHashMap<>(body);invalid.put("offeredItemIds",invalidIds);demandCall("POST","/api/demands",token,invalid,400);
        }
        for(String query:List.of("page=0","page=-1","size=0","size=101","size=abc","status=DELETED","status=unknown"))
            demandCall("GET","/api/demands?"+query,token,null,400);
        for(String query:List.of("page=0","size=0","size=101")) demandCall("GET","/api/demands/offerable-items?"+query,token,null,400);
        for(String invalidStatus:List.of("DELETED","AVAILABLE","active","")) demandCall("PATCH","/api/demands/"+id+"/status",token,Map.of("version",0,"status",invalidStatus),400);
        demandCall("DELETE","/api/demands/"+id,token,null,400);
        demandCall("DELETE","/api/demands/"+id+"?version=-1",token,null,400);
        assertEquals(created,demandCall("GET","/api/demands/"+id,token,null,200));
        JsonNode emptyFields=demandCall("PATCH","/api/demands/"+id,token,Map.of("version",0,"description","","preferredTags",List.of()),200);
        assertEquals("",emptyFields.path("description").asText());assertEquals(0,emptyFields.path("preferredTags").size());assertEquals(1,emptyFields.path("version").asInt());
    }
    @Test void demandAssociationsReuseOwnedItemsRejectDuplicatesAndApplyAtomically() throws Exception {
        String owner=demandUser(),other=demandUser();long first=demandItem(owner),second=demandItem(owner),foreign=demandItem(other);
        long itemCount=jdbc.queryForObject("SELECT COUNT(*) FROM cl_item",Long.class);
        JsonNode original=demandCall("POST","/api/demands",owner,demandBody(2,"候选集合",List.of(),List.of(second,first)),200);
        long id=original.path("id").asLong();
        assertEquals(first,original.at("/offeredItems/0/itemId").asLong());assertEquals(second,original.at("/offeredItems/1/itemId").asLong());
        assertTrue(original.at("/offeredItems/0/offerable").asBoolean());assertEquals("AVAILABLE",original.at("/offeredItems/0/status").asText());
        JsonNode another=demandCall("POST","/api/demands",owner,demandBody(3,"同一物品可在另一需求",List.of(),List.of(first)),200);
        assertEquals(first,another.at("/offeredItems/0/itemId").asLong());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM cl_demand_item WHERE item_id=?",Integer.class,first));
        assertEquals(itemCount,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item",Long.class));
        assertEquals(original.path("ownerId").asLong(),jdbc.queryForObject("SELECT owner_id FROM cl_item WHERE id=?",Long.class,first));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold WHERE item_id IN (?,?)",Integer.class,first,second));
        demandCall("POST","/api/demands",owner,demandBody(1,"重复",List.of(),List.of(first,first)),400);
        demandCall("POST","/api/demands",owner,demandBody(1,"不存在",List.of(),List.of(999999999L)),400);
        demandCall("POST","/api/demands",owner,demandBody(1,"外人物品",List.of(),List.of(foreign)),403);
        demandCall("PATCH","/api/demands/"+id,owner,Map.of("version",0,"description","不允许部分写入","offeredItemIds",List.of(first,foreign)),403);
        demandCall("PATCH","/api/demands/"+id,owner,Map.of("version",0,"offeredItemIds",List.of(first,first)),400);
        assertEquals(original,demandCall("GET","/api/demands/"+id,owner,null,200));
        JsonNode offerable=demandCall("GET","/api/demands/offerable-items?page=1&size=1",owner,null,200);
        assertEquals(2,offerable.path("total").asInt());assertEquals(1,offerable.path("records").size());assertTrue(offerable.at("/records/0/offerable").asBoolean());
        JsonNode replaced=demandCall("PATCH","/api/demands/"+id,owner,Map.of("version",0,"offeredItemIds",List.of(second)),200);
        assertEquals(1,replaced.path("offeredItems").size());assertEquals(second,replaced.at("/offeredItems/0/itemId").asLong());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_demand_item WHERE demand_id=?",Integer.class,id));
        assertEquals(2,demandCall("GET","/api/demands",owner,null,200).path("total").asInt());
    }
    @Test void demandAssociationsRejectEveryUnavailableStateAndHeldItems() throws Exception {
        String owner=demandUser();long item=demandItem(owner);
        try {
            for(String unavailable:List.of("DRAFT","PENDING_REVIEW","RESERVED","EXCHANGED","HIDDEN")) {
                jdbc.update("UPDATE cl_item SET status=? WHERE id=?",unavailable,item);
                demandCall("POST","/api/demands",owner,demandBody(1,"状态冲突",List.of(),List.of(item)),409);
                assertEquals(0,demandCall("GET","/api/demands/offerable-items",owner,null,200).path("total").asInt());
            }
        } finally {jdbc.update("UPDATE cl_item SET status='AVAILABLE' WHERE id=?",item);}
        JsonNode associated=demandCall("POST","/api/demands",owner,demandBody(1,"已有候选后来被占用",List.of(),List.of(item)),200);
        long associatedId=associated.path("id").asLong();
        long ownerId=jdbc.queryForObject("SELECT owner_id FROM cl_item WHERE id=?",Long.class,item);
        String key=UUID.randomUUID().toString();LocalDateTime expires=LocalDateTime.now(ZoneOffset.UTC).plusHours(1);
        jdbc.update("INSERT INTO cl_exchange(initiator_id,status,version,idempotency_key,expires_at) VALUES (?,'AWAITING_CONFIRMATION',0,?,?)",ownerId,key,expires);
        long exchangeId=jdbc.queryForObject("SELECT id FROM cl_exchange WHERE initiator_id=? AND idempotency_key=?",Long.class,ownerId,key);
        try {
            jdbc.update("INSERT INTO cl_item_hold(item_id,exchange_id,expires_at) VALUES (?,?,?)",item,exchangeId,expires);
            demandCall("POST","/api/demands",owner,demandBody(1,"占用冲突",List.of(),List.of(item)),409);
            assertEquals(0,demandCall("GET","/api/demands/offerable-items",owner,null,200).path("total").asInt());
            JsonNode held=demandCall("GET","/api/demands/"+associatedId,owner,null,200);
            assertFalse(held.at("/offeredItems/0/offerable").asBoolean());assertEquals("AVAILABLE",held.at("/offeredItems/0/status").asText());
            demandCall("PATCH","/api/demands/"+associatedId+"/status",owner,Map.of("version",0,"status","INACTIVE"),200);
            demandCall("PATCH","/api/demands/"+associatedId+"/status",owner,Map.of("version",1,"status","ACTIVE"),409);
            assertEquals(1,demandCall("GET","/api/demands",owner,null,200).path("total").asInt());
        } finally {
            jdbc.update("DELETE FROM cl_item_hold WHERE exchange_id=?",exchangeId);
            jdbc.update("DELETE FROM cl_exchange WHERE id=?",exchangeId);
        }
        assertEquals(1,demandCall("GET","/api/demands/offerable-items",owner,null,200).path("total").asInt());
        assertTrue(demandCall("GET","/api/demands/"+associatedId,owner,null,200).at("/offeredItems/0/offerable").asBoolean());
        demandCall("PATCH","/api/demands/"+associatedId+"/status",owner,Map.of("version",1,"status","ACTIVE"),200);
        demandCall("POST","/api/demands",owner,demandBody(1,"解除占用可关联",List.of(),List.of(item)),200);
    }
    @Test void demandReadsLiveAssociationValidityAndCanDeactivateOrClearInvalidAssociations() throws Exception {
        String owner=demandUser(),other=demandUser();long item=demandItem(owner),otherItem=demandItem(other);
        JsonNode created=demandCall("POST","/api/demands",owner,demandBody(2,"关联会失效",List.of(),List.of(item)),200);
        long id=created.path("id").asLong();
        jdbc.update("UPDATE cl_item SET status='RESERVED' WHERE id=?",item);
        try {
            JsonNode invalid=demandCall("GET","/api/demands/"+id,owner,null,200);
            assertFalse(invalid.at("/offeredItems/0/offerable").asBoolean());assertEquals("RESERVED",invalid.at("/offeredItems/0/status").asText());
            assertFalse(demandCall("GET","/api/demands",owner,null,200).at("/records/0/offeredItems/0/offerable").asBoolean());
            JsonNode edited=demandCall("PATCH","/api/demands/"+id,owner,Map.of("version",0,"description","未提交关联仍可改说明"),200);
            assertEquals(1,edited.path("offeredItems").size());assertFalse(edited.at("/offeredItems/0/offerable").asBoolean());
            demandCall("PATCH","/api/demands/"+id+"/status",owner,Map.of("version",1,"status","INACTIVE"),200);
            demandCall("PATCH","/api/demands/"+id+"/status",owner,Map.of("version",2,"status","ACTIVE"),409);
            JsonNode stillInactive=demandCall("GET","/api/demands/"+id,owner,null,200);
            assertEquals(2,stillInactive.path("version").asInt());assertEquals("INACTIVE",stillInactive.path("status").asText());
            demandCall("PATCH","/api/demands/"+id,owner,Map.of("version",2,"offeredItemIds",List.of()),200);
            JsonNode restored=demandCall("PATCH","/api/demands/"+id+"/status",owner,Map.of("version",3,"status","ACTIVE"),200);
            assertEquals(0,restored.path("offeredItems").size());
        } finally {jdbc.update("UPDATE cl_item SET status='AVAILABLE' WHERE id=?",item);}
        JsonNode historical=demandCall("POST","/api/demands",owner,demandBody(3,"归属变化只保留引用",List.of(),List.of(item)),200);
        long historicalId=historical.path("id").asLong(),ownerId=created.path("ownerId").asLong();
        long otherId=jdbc.queryForObject("SELECT owner_id FROM cl_item WHERE id=?",Long.class,otherItem);
        jdbc.update("UPDATE cl_item SET owner_id=?,status='HIDDEN' WHERE id=?",otherId,item);
        try {
            JsonNode redacted=demandCall("GET","/api/demands/"+historicalId,owner,null,200).at("/offeredItems/0");
            assertEquals(item,redacted.path("itemId").asLong());assertFalse(redacted.path("offerable").asBoolean());
            for(String field:List.of("title","categoryId","conditionLevel","status")) assertTrue(redacted.path(field).isNull(),"Historical foreign item field must be null: "+field);
            assertEquals(0,demandCall("GET","/api/demands/offerable-items",owner,null,200).path("total").asInt());
        } finally {jdbc.update("UPDATE cl_item SET owner_id=?,status='AVAILABLE' WHERE id=?",ownerId,item);}
    }
    @Test void demandVersionsProtectFieldsAssociationsStatusAndDeleteDuringConcurrentEdits() throws Exception {
        String owner=demandUser();long item=demandItem(owner);
        JsonNode created=demandCall("POST","/api/demands",owner,demandBody(2,"初始",List.of("保留"),List.of(item)),200);
        long id=created.path("id").asLong();
        JsonNode updated=demandCall("PATCH","/api/demands/"+id,owner,Map.of("version",0,"description","已更新"),200);
        demandCall("PATCH","/api/demands/"+id,owner,Map.of("version",0,"description","过期内容","offeredItemIds",List.of()),409);
        demandCall("PATCH","/api/demands/"+id+"/status",owner,Map.of("version",0,"status","INACTIVE"),409);
        demandCall("DELETE","/api/demands/"+id+"?version=0",owner,null,409);
        assertEquals(updated,demandCall("GET","/api/demands/"+id,owner,null,200));
        ExecutorService executor=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try {
            List<Future<Integer>> writes=new ArrayList<>();
            for(String description:List.of("并发甲","并发乙")) writes.add(executor.submit(()->{
                ready.countDown();
                try {
                    if(!start.await(10,TimeUnit.SECONDS)) return -1;
                    return mvc.perform(patch("/api/demands/"+id).header("Authorization","Bearer "+owner).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("version",1,"description",description)))).andReturn().getResponse().getStatus();
                } catch(Exception failure) {return -2;} // Return only a safe outcome; never propagate an authenticated request dump.
            }));
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            List<Integer> outcomes=new ArrayList<>();for(Future<Integer> write:writes) outcomes.add(write.get(30,TimeUnit.SECONDS));
            Collections.sort(outcomes);assertEquals(List.of(200,409),outcomes);
        } finally {start.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
        JsonNode finalState=demandCall("GET","/api/demands/"+id,owner,null,200);
        assertEquals(2,finalState.path("version").asInt());assertTrue(Set.of("并发甲","并发乙").contains(finalState.path("description").asText()));
        assertEquals(created.path("offeredItems"),finalState.path("offeredItems"));assertEquals(created.path("preferredTags"),finalState.path("preferredTags"));
        assertEquals(finalState.path("description").asText(),jdbc.queryForObject("SELECT description FROM cl_demand WHERE id=?",String.class,id));
        assertEquals(2,jdbc.queryForObject("SELECT version FROM cl_demand WHERE id=?",Integer.class,id));
    }
    @Test void demandDeletionKeepsTombstoneAndAssociationsAndPreventsPhysicalRemoval() throws Exception {
        String owner=demandUser();long item=demandItem(owner);
        JsonNode created=demandCall("POST","/api/demands",owner,demandBody(2,"保留原始需求供历史引用",List.of("标签"),List.of(item)),200);
        long id=created.path("id").asLong();
        JsonNode deleted=demandCall("DELETE","/api/demands/"+id+"?version=0",owner,null,200);
        assertEquals(id,deleted.path("id").asLong());assertEquals("DELETED",deleted.path("status").asText());assertEquals(1,deleted.path("version").asInt());
        assertEquals("DELETED",jdbc.queryForObject("SELECT status FROM cl_demand WHERE id=?",String.class,id));
        assertEquals("保留原始需求供历史引用",jdbc.queryForObject("SELECT description FROM cl_demand WHERE id=?",String.class,id));
        assertEquals(created.path("ownerId").asLong(),jdbc.queryForObject("SELECT owner_id FROM cl_demand WHERE id=?",Long.class,id));
        assertEquals(2,jdbc.queryForObject("SELECT category_id FROM cl_demand WHERE id=?",Integer.class,id));
        assertEquals(json.valueToTree(List.of("标签")),json.readTree(jdbc.queryForObject("SELECT preferred_tags_json FROM cl_demand WHERE id=?",String.class,id)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_demand_item WHERE demand_id=? AND item_id=?",Integer.class,id,item));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("DELETE FROM cl_demand WHERE id=?",id));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("DELETE FROM cl_item WHERE id=?",item));
        demandCall("GET","/api/demands/"+id,owner,null,404);
        demandCall("PATCH","/api/demands/"+id,owner,Map.of("version",1,"description","无法恢复"),404);
        demandCall("PATCH","/api/demands/"+id+"/status",owner,Map.of("version",1,"status","ACTIVE"),404);
        demandCall("DELETE","/api/demands/"+id+"?version=1",owner,null,404);
        assertEquals(0,demandCall("GET","/api/demands",owner,null,200).path("total").asInt());
        assertEquals(1,demandCall("GET","/api/demands/offerable-items",owner,null,200).path("total").asInt());
        JsonNode inactive=demandCall("POST","/api/demands",owner,demandBody(1,"停用后也可删除",List.of(),List.of()),200);long inactiveId=inactive.path("id").asLong();
        demandCall("PATCH","/api/demands/"+inactiveId+"/status",owner,Map.of("version",0,"status","INACTIVE"),200);
        demandCall("DELETE","/api/demands/"+inactiveId+"?version=1",owner,null,200);
    }
    @Test void independentDemandChangesNeverRewriteLegacyItemWantsOrRecommendations() throws Exception {
        String owner=demandUser();long item=demandItem(owner);
        JsonNode before=demandCall("GET","/api/matches",null,null,200);
        String legacyTags=jdbc.queryForObject("SELECT wanted_tags_json FROM cl_item WHERE id=?",String.class,item);
        long legacyCategory=jdbc.queryForObject("SELECT wanted_category_id FROM cl_item WHERE id=?",Long.class,item);
        long holds=jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold",Long.class),exchanges=jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange",Long.class);
        JsonNode created=demandCall("POST","/api/demands",owner,demandBody(6,"独立绿植需求",List.of("耐养"),List.of(item)),200);long id=created.path("id").asLong();
        assertEquals(before,demandCall("GET","/api/matches",null,null,200));
        demandCall("PATCH","/api/demands/"+id,owner,Map.of("version",0,"categoryId",5,"preferredTags",List.of("手作")),200);
        assertEquals(before,demandCall("GET","/api/matches",null,null,200));
        demandCall("PATCH","/api/demands/"+id+"/status",owner,Map.of("version",1,"status","INACTIVE"),200);
        assertEquals(before,demandCall("GET","/api/matches",null,null,200));
        demandCall("DELETE","/api/demands/"+id+"?version=2",owner,null,200);
        assertEquals(before,demandCall("GET","/api/matches",null,null,200));
        assertEquals(legacyCategory,jdbc.queryForObject("SELECT wanted_category_id FROM cl_item WHERE id=?",Long.class,item));
        assertEquals(legacyTags,jdbc.queryForObject("SELECT wanted_tags_json FROM cl_item WHERE id=?",String.class,item));
        assertEquals(holds,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold",Long.class));assertEquals(exchanges,jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange",Long.class));
    }

    @Test void ownItemsAreSessionScopedAcrossAllStatesAndPages() throws Exception {
        String owner=demandUser(),other=demandUser();
        List<String> states=List.of("DRAFT","PENDING_REVIEW","AVAILABLE","RESERVED","EXCHANGED","HIDDEN");
        List<Long> ids=new ArrayList<>();
        for(String state:states) {
            long id=demandItem(owner);ids.add(id);
            jdbc.update("UPDATE cl_item SET status=?,created_at='2026-01-01 00:00:00' WHERE id=?",state,id);
        }
        long foreign=demandItem(other);
        long ownerId=jdbc.queryForObject("SELECT owner_id FROM cl_item WHERE id=?",Long.class,ids.get(0));
        long otherId=jdbc.queryForObject("SELECT owner_id FROM cl_item WHERE id=?",Long.class,foreign);
        JsonNode first=demandCall("GET","/api/items/mine?page=1&size=2&ownerId="+otherId,owner,null,200);
        JsonNode second=demandCall("GET","/api/items/mine?page=2&size=2",owner,null,200);
        assertEquals(6,first.path("total").asInt());assertEquals(2,first.path("size").asInt());
        assertEquals(ids.get(5).longValue(),first.at("/records/0/id").asLong());
        assertEquals(ids.get(3).longValue(),second.at("/records/0/id").asLong());
        JsonNode all=demandCall("GET","/api/items/mine",owner,null,200);
        Set<String> seen=new HashSet<>();
        for(JsonNode item:all.path("records")) {
            assertEquals(ownerId,item.path("ownerId").asLong());seen.add(item.path("status").asText());
            assertEquals(0,item.path("version").asInt());
            demandCall("GET","/api/items/mine/"+item.path("id").asLong(),other,null,403);
        }
        assertEquals(new HashSet<>(states),seen);
        for(String state:states) assertEquals(1,demandCall("GET","/api/items/mine?status="+state+"&categoryId=1&keyword=需求关联测试",owner,null,200).path("total").asInt());
        assertEquals(0,demandCall("GET","/api/items/mine?categoryId=6",owner,null,200).path("total").asInt());
        assertEquals(0,demandCall("GET","/api/items/mine",demandUser(),null,200).path("total").asInt());
        assertTrue(demandCall("GET","/api/items/mine?page=20",owner,null,200).path("records").isEmpty());
        for(String query:List.of("page=0","size=101","categoryId=0","status=READY","keyword="+"a".repeat(101)))
            demandCall("GET","/api/items/mine?"+query,owner,null,400);
        demandCall("GET","/api/items/mine",null,null,401);
        demandCall("GET","/api/items/mine/"+ids.get(5),null,null,401);
        demandCall("GET","/api/items/mine/999999999",owner,null,404);
        demandCall("GET","/api/items/mine/"+ids.get(5),adminToken,null,403);
        // Public visibility remains the existing three-state contract.
        for(int i=0;i<states.size();i++) demandCall("GET","/api/items/"+ids.get(i),null,null,
            Set.of("AVAILABLE","RESERVED","EXCHANGED").contains(states.get(i))?200:404);
    }

    @Test void ownItemEditReadsBackAllFieldsAndRejectsOtherOwnersAndStaleVersions() throws Exception {
        String owner=demandUser(),other=demandUser();long id=demandItem(owner);
        Map<String,Object> before=itemRow(id),body=itemEdit(0,"  更新后标题  ");
        body.put("description","  更新后的说明  ");body.put("categoryId",3);body.put("wantedCategoryId",6);
        body.put("conditionLevel",2);body.put("tags",List.of(" Green ","green","运动"));body.put("wantedTags",List.of(" Small "));
        for(String token:List.of(other,adminToken)) {
            demandCall("PUT","/api/items/"+id,token,body,403);
            demandCall("POST","/api/items/"+id+"/withdraw",token,Map.of("version",0),403);
        }
        demandCall("PUT","/api/items/"+id,null,body,401);
        demandCall("POST","/api/items/"+id+"/withdraw",null,Map.of("version",0),401);
        demandCall("PUT","/api/items/999999999",owner,body,404);
        demandCall("POST","/api/items/999999999/withdraw",owner,Map.of("version",0),404);
        assertEquals(before,itemRow(id));
        JsonNode edited=demandCall("PUT","/api/items/"+id,owner,body,200);
        assertEquals("更新后标题",edited.path("title").asText());assertEquals("更新后的说明",edited.path("description").asText());
        assertEquals(List.of("green","运动"),json.convertValue(edited.path("tags"),List.class));
        assertEquals(List.of("small"),json.convertValue(edited.path("wantedTags"),List.class));
        assertEquals(1,edited.path("version").asInt());assertEquals(3,edited.path("categoryId").asInt());
        assertEquals(6,edited.path("wantedCategoryId").asInt());assertEquals(2,edited.path("conditionLevel").asInt());
        Map<String,Object> stored=itemRow(id);
        assertEquals("更新后标题",stored.get("title"));assertEquals("更新后的说明",stored.get("description"));
        assertEquals(3,((Number)stored.get("category_id")).intValue());assertEquals(6,((Number)stored.get("wanted_category_id")).intValue());
        assertEquals(2,stored.get("condition_level"));assertEquals(1,stored.get("version"));
        assertEquals(edited.path("tags"),json.readTree((String)stored.get("tags_json")));
        assertEquals(edited.path("wantedTags"),json.readTree((String)stored.get("wanted_tags_json")));
        assertEquals(before.get("owner_id"),stored.get("owner_id"));assertEquals(before.get("created_at"),stored.get("created_at"));
        assertEquals(edited,demandCall("GET","/api/items/mine/"+id,owner,null,200));
        assertEquals("PENDING_REVIEW",edited.path("status").asText());
        demandCall("GET","/api/items/"+id,null,null,404);
        demandCall("PUT","/api/items/"+id,owner,body,409);
        demandCall("POST","/api/items/"+id+"/withdraw",owner,Map.of("version",0),409);
        assertEquals(stored,itemRow(id));
    }

    @Test void ownItemWritesValidateWhitelistTypesAndPublishConstraintsAtomically() throws Exception {
        String owner=demandUser();long id=demandItem(owner);Map<String,Object> before=itemRow(id);
        for(String protectedField:List.of("id","ownerId","owner","role","status","createdAt","fields","unknown")) {
            Map<String,Object> body=itemEdit(0,"禁止部分写入");body.put(protectedField,1);
            demandCall("PUT","/api/items/"+id,owner,body,400);
            demandCall("POST","/api/items/"+id+"/withdraw",owner,Map.of("version",0,protectedField,1),400);
        }
        for(String required:List.of("version","title","description","categoryId","conditionLevel","tags","wantedCategoryId","wantedTags")) {
            Map<String,Object> body=itemEdit(0,"缺少字段");body.remove(required);
            demandCall("PUT","/api/items/"+id,owner,body,400);
            body.put(required,null);demandCall("PUT","/api/items/"+id,owner,body,400);
        }
        Map<String,List<Object>> invalid=new LinkedHashMap<>();
        invalid.put("version",List.of(-1,"0",0.5,2147483648L));
        invalid.put("title",List.of(" ","x".repeat(101),42));
        invalid.put("description",List.of(" ","x".repeat(2001),false));
        invalid.put("categoryId",List.of(0,999999999,"1",1.5));
        invalid.put("wantedCategoryId",List.of(0,999999999,"2"));
        invalid.put("conditionLevel",List.of(0,6,1.5,"4"));
        invalid.put("tags",List.of("标签",List.of(1),List.of(" "),List.of("x".repeat(21)),Collections.nCopies(9,"标签")));
        invalid.put("wantedTags",List.of(List.of(" "),List.of("x".repeat(21)),Collections.nCopies(9,"偏好")));
        invalid.put("imageUrl",List.of(12,"x".repeat(256)));
        for(var entry:invalid.entrySet()) for(Object value:entry.getValue()) {
            Map<String,Object> body=itemEdit(0,"校验失败不应写入");body.put(entry.getKey(),value);
            demandCall("PUT","/api/items/"+id,owner,body,400);
            if(entry.getKey().equals("version")) demandCall("POST","/api/items/"+id+"/withdraw",owner,Map.of("version",value),400);
        }
        demandCall("POST","/api/items/"+id+"/withdraw",owner,Map.of(),400);
        demandCall("POST","/api/items/"+id+"/withdraw",owner,Collections.singletonMap("version",null),400);
        assertEquals(before,itemRow(id));
        jdbc.update("UPDATE cl_item SET version=? WHERE id=?",Integer.MAX_VALUE,id);
        demandCall("PUT","/api/items/"+id,owner,itemEdit(Integer.MAX_VALUE,"不可溢出"),409);
        demandCall("POST","/api/items/"+id+"/withdraw",owner,Map.of("version",Integer.MAX_VALUE),409);
        assertEquals(Integer.MAX_VALUE,itemRow(id).get("version"));
    }

    @Test void ownItemImageEditsReuseUploadOwnershipAndOnlyRemoveReferences() throws Exception {
        String owner=demandUser(),other=demandUser();long id=demandItem(owner);
        String mine=itemUpload(owner),foreign=itemUpload(other);Map<String,Object> body=itemEdit(0,"图片替换");
        body.put("imageUrl",mine);demandCall("PUT","/api/items/"+id,owner,body,200);
        Map<String,Object> before=itemRow(id);
        for(String image:List.of(foreign,"/etc/passwd","/uploads/../file.png","https://untrusted.invalid/a.png","/uploads/"+UUID.randomUUID()+".png")) {
            body=itemEdit(1,"非法图片不得部分写入");body.put("imageUrl",image);
            demandCall("PUT","/api/items/"+id,owner,body,400);assertEquals(before,itemRow(id));
        }
        int version=1;
        for(String clear:List.of("null","omitted","blank")) {
            body=itemEdit(version,"清除图片引用");
            if(clear.equals("null")) body.put("imageUrl",null);
            if(clear.equals("blank")) body.put("imageUrl","  ");
            JsonNode cleared=demandCall("PUT","/api/items/"+id,owner,body,200);version++;
            assertTrue(cleared.path("imageUrl").isNull());assertNull(itemRow(id).get("image_url"));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_upload WHERE url=?",Integer.class,mine));
            assertTrue(Files.exists(UPLOADS.resolve(mine.substring("/uploads/".length()))));
            body=itemEdit(version,"恢复本人图引用");body.put("imageUrl",mine);
            demandCall("PUT","/api/items/"+id,owner,body,200);version++;
        }
    }

    @Test void ownItemWritesRejectEveryUnavailableStateAndActiveExchangeReference() throws Exception {
        String owner=demandUser();long id=demandItem(owner);
        for(String state:List.of("DRAFT","RESERVED","EXCHANGED","HIDDEN")) {
            jdbc.update("UPDATE cl_item SET status=? WHERE id=?",state,id);assertItemWritesBlocked(owner,id);
        }
        jdbc.update("UPDATE cl_item SET status='AVAILABLE' WHERE id=?",id);
        long exchange=itemExchange(id);
        try {
            // A stale AVAILABLE flag cannot bypass an outstanding hold, even after expiry.
            for(int offset:List.of(-1,1)) {
                jdbc.update("INSERT INTO cl_item_hold(item_id,exchange_id,expires_at) VALUES (?,?,?)",id,exchange,LocalDateTime.now(ZoneOffset.UTC).plusHours(offset));
                assertItemWritesBlocked(owner,id);
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold WHERE item_id=?",Integer.class,id));
                jdbc.update("DELETE FROM cl_item_hold WHERE item_id=?",id);
            }
            long ownerId=((Number)itemRow(id).get("owner_id")).longValue();
            jdbc.update("INSERT INTO cl_exchange_participant(exchange_id,user_id,offered_item_id,recipient_user_id,handed_off_at) VALUES (?,?,?,?,?)",
                exchange,ownerId,id,memberId,LocalDateTime.now(ZoneOffset.UTC));
            for(String state:List.of("AWAITING_CONFIRMATION","READY","DISPUTED")) {
                jdbc.update("UPDATE cl_exchange SET status=? WHERE id=?",state,exchange);assertItemWritesBlocked(owner,id);
            }
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange_participant WHERE exchange_id=?",Integer.class,exchange));
            jdbc.update("UPDATE cl_exchange SET status='COMPLETED' WHERE id=?",exchange);
            demandCall("POST","/api/items/"+id+"/withdraw",owner,Map.of("version",0),200);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange_participant WHERE exchange_id=?",Integer.class,exchange));
        } finally {cleanItemExchange(exchange);}
    }

    @Test void withdrawalPreservesHistoryAndDemandLinksAndExitsPublicReadsAndMatching() throws Exception {
        String owner=demandUser(),other=demandUser();long id=demandItem(owner),partner=demandItem(other);
        jdbc.update("UPDATE cl_item SET category_id=2,wanted_category_id=1 WHERE id=?",partner);
        long demand=demandCall("POST","/api/demands",owner,demandBody(2,"下架保留需求",List.of(),List.of(id)),200).path("id").asLong();
        demandCall("POST","/api/demands",other,demandBody(1,"接收图书",List.of(),List.of(partner)),200);
        assertTrue(matchesContainItem(readOnlyIndependentMatches(owner,200).path("recommendations"),id));
        long ownerId=((Number)itemRow(id).get("owner_id")).longValue();
        jdbc.update("INSERT INTO cl_item_history(item_id,event_type,description,evidence_level,source_user_id,occurred_at) VALUES (?,'STATEMENT','虚构隔离履历','SELF_REPORTED',?,?)",
            id,ownerId,LocalDateTime.now(ZoneOffset.UTC));
        assertTrue(matchesContainItem(id));Map<String,Object> before=itemRow(id);
        JsonNode hidden=demandCall("POST","/api/items/"+id+"/withdraw",owner,Map.of("version",0),200);
        assertEquals("HIDDEN",hidden.path("status").asText());assertEquals(1,hidden.path("version").asInt());
        Map<String,Object> expected=new HashMap<>(before);expected.put("status","HIDDEN");expected.put("version",1);
        assertEquals(expected,itemRow(id));assertEquals(hidden,demandCall("GET","/api/items/mine/"+id,owner,null,200));
        assertEquals(1,demandCall("GET","/api/items/mine?status=HIDDEN",owner,null,200).path("total").asInt());
        demandCall("GET","/api/items/"+id,null,null,404);
        assertEquals(0,demandCall("GET","/api/items?keyword="+java.net.URLEncoder.encode(hidden.path("title").asText(),java.nio.charset.StandardCharsets.UTF_8),null,null,200).path("total").asInt());
        assertFalse(matchesContainItem(id));
        assertFalse(matchesContainItem(readOnlyIndependentMatches(owner,200).path("recommendations"),id));
        assertFalse(matchesContainItem(readOnlyIndependentMatches(other,200).path("recommendations"),id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_history WHERE item_id=?",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_demand_item WHERE item_id=? AND demand_id=?",Integer.class,id,demand));
        assertFalse(demandCall("GET","/api/demands/"+demand,owner,null,200).at("/offeredItems/0/offerable").asBoolean());
        demandCall("POST","/api/items/"+id+"/withdraw",owner,Map.of("version",1),409);
        demandCall("PUT","/api/items/"+id,owner,itemEdit(1,"不支持重新上架"),409);
        assertEquals(expected,itemRow(id));
    }

    @Test void concurrentItemEditsAndWithdrawalsHaveExactlyOneWinner() throws Exception {
        String owner=demandUser();
        for(List<String> actions:List.of(List.of("PUT","PUT"),List.of("PUT","POST"),List.of("POST","POST"))) {
            long id=demandItem(owner);ExecutorService pool=Executors.newFixedThreadPool(2);
            CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
            try {
                List<Future<MvcResult>> futures=new ArrayList<>();
                for(int i=0;i<2;i++) {
                    String method=actions.get(i),path="/api/items/"+id+(method.equals("POST")?"/withdraw":"");
                    Object body=method.equals("POST")?Map.of("version",0):itemEdit(0,"并发编辑 "+i);
                    futures.add(pool.submit(()->{ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));
                        return mvc.perform(request(HttpMethod.valueOf(method),path).header("Authorization","Bearer "+owner)
                            .contentType("application/json").content(json.writeValueAsString(body))).andReturn();}));
                }
                assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
                List<Integer> codes=new ArrayList<>();JsonNode winner=null;
                for(Future<MvcResult> future:futures) {
                    MvcResult result=future.get(15,TimeUnit.SECONDS);codes.add(result.getResponse().getStatus());
                    JsonNode response=json.readTree(result.getResponse().getContentAsString());
                    assertEquals(result.getResponse().getStatus(),response.path("code").asInt());
                    if(result.getResponse().getStatus()==200) winner=response.path("data");
                }
                codes.sort(Integer::compareTo);assertEquals(List.of(200,409),codes);assertNotNull(winner);
                assertEquals(1,itemRow(id).get("version"));assertEquals(winner.path("title").asText(),itemRow(id).get("title"));
                assertEquals(winner.path("status").asText(),itemRow(id).get("status"));
                assertEquals(winner,demandCall("GET","/api/items/mine/"+id,owner,null,200));
            } finally {start.countDown();pool.shutdownNow();}
        }
    }

    @Test void waitingItemWritesRecheckCommittedHoldStateAndOwnership() throws Exception {
        String owner=demandUser();
        for(String change:List.of("hold","state","owner","participant")) for(String method:List.of("PUT","POST")) {
            long id=demandItem(owner),exchange=itemExchange(id);ExecutorService pool=Executors.newSingleThreadExecutor();
            String path="/api/items/"+id+(method.equals("POST")?"/withdraw":"");
            Object body=method.equals("POST")?Map.of("version",0):itemEdit(0,"等待锁的写入");
            try {
                Future<MvcResult> pending=new TransactionTemplate(transactions).execute(tx->{
                    jdbc.queryForObject("SELECT id FROM cl_item WHERE id=? FOR UPDATE",Long.class,id);
                    CountDownLatch attempting=new CountDownLatch(1);
                    Future<MvcResult> future=pool.submit(()->{attempting.countDown();return mvc.perform(request(HttpMethod.valueOf(method),path)
                        .header("Authorization","Bearer "+owner).contentType("application/json").content(json.writeValueAsString(body))).andReturn();});
                    try {
                        assertTrue(attempting.await(5,TimeUnit.SECONDS));
                        assertThrows(TimeoutException.class,()->future.get(250,TimeUnit.MILLISECONDS));
                    } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                    if(change.equals("hold")) jdbc.update("INSERT INTO cl_item_hold(item_id,exchange_id,expires_at) VALUES (?,?,?)",id,exchange,LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
                    if(change.equals("state")) jdbc.update("UPDATE cl_item SET status='RESERVED' WHERE id=?",id);
                    if(change.equals("owner")) jdbc.update("UPDATE cl_item SET owner_id=? WHERE id=?",adminId,id);
                    if(change.equals("participant")) jdbc.update("INSERT INTO cl_exchange_participant(exchange_id,user_id,offered_item_id,recipient_user_id,handed_off_at) VALUES (?,?,?,?,?)",
                        exchange,((Number)itemRow(id).get("owner_id")).longValue(),id,memberId,LocalDateTime.now(ZoneOffset.UTC));
                    return future;
                });
                MvcResult result=Objects.requireNonNull(pending).get(15,TimeUnit.SECONDS);
                assertEquals(change.equals("owner")?403:409,result.getResponse().getStatus());
                assertEquals(0,itemRow(id).get("version"));assertNotEquals("等待锁的写入",itemRow(id).get("title"));
                assertNotEquals("HIDDEN",itemRow(id).get("status"));
            } finally {pool.shutdownNow();cleanItemExchange(exchange);}
        }
    }

    private Map<String,Object> itemEdit(int version,String title) {
        return new HashMap<>(Map.of("version",version,"title",title,"description","隔离数据库物品编辑",
            "categoryId",1,"conditionLevel",4,"tags",List.of("教材"),"wantedCategoryId",2,"wantedTags",List.of("便携")));
    }
    private Map<String,Object> itemRow(long id) {return jdbc.queryForMap("SELECT * FROM cl_item WHERE id=?",id);}
    private void assertItemWritesBlocked(String owner,long id) throws Exception {
        Map<String,Object> before=itemRow(id);
        demandCall("PUT","/api/items/"+id,owner,itemEdit(0,"状态保护"),409);
        demandCall("POST","/api/items/"+id+"/withdraw",owner,Map.of("version",0),409);
        assertEquals(before,itemRow(id));
    }
    private String itemUpload(String token) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",out);
        MvcResult result=mvc.perform(multipart("/api/uploads").file(new MockMultipartFile("file","sample.png","image/png",out.toByteArray()))
            .header("Authorization","Bearer "+token)).andReturn();
        assertEquals(200,result.getResponse().getStatus());return json.readTree(result.getResponse().getContentAsString()).at("/data/url").asText();
    }
    private boolean matchesContainItem(long id) throws Exception {
        for(JsonNode match:demandCall("GET","/api/matches",null,null,200)) for(JsonNode participant:match.path("participants"))
            if(participant.path("itemId").asLong()==id) return true;
        return false;
    }
    private long itemExchange(long id) {
        long ownerId=((Number)itemRow(id).get("owner_id")).longValue();String key=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO cl_exchange(initiator_id,status,version,idempotency_key,expires_at) VALUES (?,'READY',0,?,?)",
            ownerId,key,LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        return jdbc.queryForObject("SELECT id FROM cl_exchange WHERE initiator_id=? AND idempotency_key=?",Long.class,ownerId,key);
    }
    private void cleanItemExchange(long exchange) {
        jdbc.update("DELETE FROM cl_item_hold WHERE exchange_id=?",exchange);
        jdbc.update("DELETE FROM cl_exchange_participant WHERE exchange_id=?",exchange);
        jdbc.update("DELETE FROM cl_exchange WHERE id=?",exchange);
    }

    @Test void independentSnapshotUsesLiveEligibleAssociationsWithoutWritingDomainRows() throws Exception {
        List<Long> fixtureOwners=new ArrayList<>();
        try {
            String owner=demandUser(),recipient=demandUser(),disabled=demandUser();
            long ownerId=matchingOwnerId(owner),recipientId=matchingOwnerId(recipient),disabledId=matchingOwnerId(disabled);
            fixtureOwners.addAll(List.of(ownerId,recipientId,disabledId));
            long available=demandItem(owner),unavailable=demandItem(owner),transferred=demandItem(owner),held=demandItem(owner),disabledItem=demandItem(disabled);
            JsonNode active=demandCall("POST","/api/demands",owner,demandBody(6,"独立需求快照",List.of(" USB ","便携"),List.of(available,unavailable,transferred,held)),200);
            long activeId=active.path("id").asLong();
            demandCall("PATCH","/api/demands/"+activeId,owner,Map.of("version",0,"preferredTags",List.of("solar","便携")),200);
            long inactiveId=demandCall("POST","/api/demands",owner,demandBody(3,"停用不入快照",List.of(),List.of(available)),200).path("id").asLong();
            demandCall("PATCH","/api/demands/"+inactiveId+"/status",owner,Map.of("version",0,"status","INACTIVE"),200);
            long deletedId=demandCall("POST","/api/demands",owner,demandBody(4,"墓碑不入快照",List.of(),List.of(available)),200).path("id").asLong();
            demandCall("DELETE","/api/demands/"+deletedId+"?version=0",owner,null,200);
            long unlinkedId=demandCall("POST","/api/demands",owner,demandBody(2,"无关联可保存但不入匹配",List.of(),List.of()),200).path("id").asLong();
            long disabledDemand=demandCall("POST","/api/demands",disabled,demandBody(1,"停用用户不入快照",List.of(),List.of(disabledItem)),200).path("id").asLong();
            jdbc.update("UPDATE cl_item SET owner_id=? WHERE id=?",recipientId,transferred);
            jdbc.update("UPDATE cl_user SET status='DISABLED' WHERE id=?",disabledId);
            createMatchingHold(ownerId,held);
            for(String itemStatus:List.of("DRAFT","PENDING_REVIEW","RESERVED","EXCHANGED","HIDDEN")) {
                jdbc.update("UPDATE cl_item SET status=? WHERE id=?",itemStatus,unavailable);
                IndependentMatchingInput snapshot=readOnlyMatchingSnapshot();
                Set<Long> offerIds=new HashSet<>();snapshot.offers().forEach(offer->offerIds.add(offer.id()));
                assertTrue(offerIds.contains(available));assertTrue(offerIds.contains(transferred));
                assertFalse(offerIds.contains(unavailable));assertFalse(offerIds.contains(held));assertFalse(offerIds.contains(disabledItem));
                IndependentMatchingInput.Offer transferredOffer=snapshot.offers().stream().filter(offer->offer.id()==transferred).findFirst().orElseThrow();
                assertEquals(recipientId,transferredOffer.ownerId());
                for(IndependentMatchingInput.Offer offer:snapshot.offers()) {
                    assertEquals("AVAILABLE",offer.status());assertEquals("ACTIVE",offer.userStatus());assertFalse(offer.held());
                }
                List<IndependentMatchingInput.Demand> owned=snapshot.demands().stream().filter(demand->fixtureOwners.contains(demand.ownerId())).toList();
                assertEquals(1,owned.size());IndependentMatchingInput.Demand selected=owned.get(0);
                assertEquals(activeId,selected.id());assertEquals(ownerId,selected.ownerId());assertEquals(6,selected.categoryId());
                assertEquals(Set.of("solar","便携"),selected.preferredTags());assertEquals(Set.of(available),selected.offeredItemIds());
                assertEquals("ACTIVE",selected.status());assertEquals(1,selected.version());
                assertTrue(snapshot.demands().stream().noneMatch(demand->Set.of(inactiveId,deletedId,unlinkedId,disabledDemand).contains(demand.id())));
            }
            readOnlyLegacyMatches(200);
            assertEquals(2,jdbc.queryForObject("SELECT wanted_category_id FROM cl_item WHERE id=?",Integer.class,available));
            assertEquals(json.valueToTree(List.of("便携")),json.readTree(jdbc.queryForObject("SELECT wanted_tags_json FROM cl_item WHERE id=?",String.class,available)));
        } finally {deleteMatchingFixtureOwners(fixtureOwners);}
    }
    @Test void legacyRecommendationsExcludeHeldItemsWithoutWritingDomainRows() throws Exception {
        long heldItem=2001;
        JsonNode original=readOnlyLegacyMatches(200);
        assertTrue(matchesContainItem(original,heldItem));
        long exchangeId=createMatchingHold(1001,heldItem);
        try {
            JsonNode filtered=readOnlyLegacyMatches(200);
            assertFalse(matchesContainItem(filtered,heldItem));
            assertEquals("AVAILABLE",jdbc.queryForObject("SELECT status FROM cl_item WHERE id=?",String.class,heldItem));
            assertFalse(readOnlyMatchingSnapshot().offers().stream().anyMatch(offer->offer.id()==heldItem));
        } finally {
            jdbc.update("DELETE FROM cl_item_hold WHERE exchange_id=?",exchangeId);
            jdbc.update("DELETE FROM cl_exchange WHERE id=?",exchangeId);
        }
        assertEquals(original,readOnlyLegacyMatches(200));
    }
    @Test void matchingCandidateLimitAppliesAtTwoHundredBeforeIndependentDemandFiltering() throws Exception {
        Map<String,List<Map<String,Object>>> baselineRows=matchingDomainRows();
        String token=demandUser();long ownerId=matchingOwnerId(token);
        try {
            int baseline=eligibleMatchingItemCount();assertTrue(baseline<200);
            for(int index=baseline;index<200;index++) demandCall("POST","/api/items",token,Map.of(
                "title","候选边界 "+UUID.randomUUID(),"description","隔离候选上限夹具","categoryId",6,"conditionLevel",4,
                "tags",List.of(),"wantedCategoryId",6,"wantedTags",List.of()),200);
            jdbc.update("UPDATE cl_item SET status='AVAILABLE',review_basis='LEGACY_DIRECT' WHERE owner_id=?",ownerId);
            assertEquals(200,eligibleMatchingItemCount());
            IndependentMatchingInput atLimit=readOnlyMatchingSnapshot();assertEquals(200,atLimit.offers().size());
            assertTrue(atLimit.demands().stream().noneMatch(demand->demand.ownerId()==ownerId));
            readOnlyLegacyMatches(200);
            assertEquals(0,readOnlyIndependentMatches(token,200).path("recommendations").size());
            demandCall("POST","/api/items",token,Map.of("title","第201个候选 "+UUID.randomUUID(),"description","无独立需求也占候选预算",
                "categoryId",6,"conditionLevel",4,"tags",List.of(),"wantedCategoryId",6,"wantedTags",List.of()),200);
            jdbc.update("UPDATE cl_item SET status='AVAILABLE',review_basis='LEGACY_DIRECT' WHERE owner_id=?",ownerId);
            assertEquals(201,eligibleMatchingItemCount());
            assertReadOnlySnapshotRejected(422);
            readOnlyLegacyMatches(422);
            readOnlyIndependentMatches(token,422);
        } finally {deleteMatchingFixtureOwners(List.of(ownerId));}
        assertMatchingDomainRowsUnchanged(baselineRows);
    }
    @Test void independentSnapshotLimitsEligibleAssociationsAtTwentyThousandWithoutPartialResults() throws Exception {
        Map<String,List<Map<String,Object>>> baselineRows=matchingDomainRows();
        String token=demandUser();long ownerId=matchingOwnerId(token);
        try {
            int existingItems=eligibleMatchingItemCount();
            int fixtureItemCount=Math.min(100,200-existingItems);assertTrue(fixtureItemCount>0);
            int existingAssociations=jdbc.queryForObject("SELECT COUNT(*) FROM cl_demand d JOIN cl_demand_item di ON di.demand_id=d.id "
                +"JOIN cl_item i ON i.id=di.item_id AND i.owner_id=d.owner_id JOIN cl_user u ON u.id=i.owner_id "
                +"WHERE d.status='ACTIVE' AND i.status='AVAILABLE' AND u.status='ACTIVE' "
                +"AND NOT EXISTS (SELECT 1 FROM cl_item_hold h WHERE h.item_id=i.id)",Integer.class);
            int neededAssociations=IndependentMatchingSnapshotService.MAX_ACTIVE_ASSOCIATIONS-existingAssociations;
            assertTrue(neededAssociations>0);
            int activeDemandCount=(neededAssociations+fixtureItemCount-1)/fixtureItemCount;
            List<Object[]> itemRows=new ArrayList<>(),demandRows=new ArrayList<>();
            for(int index=0;index<fixtureItemCount;index++) itemRows.add(new Object[]{ownerId,"关联边界物品 "+index});
            for(int index=0;index<=activeDemandCount;index++) demandRows.add(new Object[]{ownerId,"关联边界需求 "+index,index==activeDemandCount?"INACTIVE":"ACTIVE"});
            // One transaction avoids committing 20000 individual fixture inserts on disposable MySQL.
            new TransactionTemplate(transactions).executeWithoutResult(status->{
                jdbc.batchUpdate("INSERT INTO cl_item(owner_id,title,description,category_id,condition_level,tags_json,wanted_category_id,wanted_tags_json,status) "
                    +"VALUES (?,?,'隔离批量夹具',6,4,'[]',6,'[]','AVAILABLE')",itemRows);
                jdbc.batchUpdate("INSERT INTO cl_demand(owner_id,description,category_id,preferred_tags_json,status) VALUES (?,?,6,'[]',?)",demandRows);
            });
            List<Long> itemIds=jdbc.queryForList("SELECT id FROM cl_item WHERE owner_id=? ORDER BY id",Long.class,ownerId);
            List<Long> demandIds=jdbc.queryForList("SELECT id FROM cl_demand WHERE owner_id=? ORDER BY id",Long.class,ownerId);
            assertEquals(fixtureItemCount,itemIds.size());assertEquals(activeDemandCount+1,demandIds.size());
            List<Object[]> associationRows=new ArrayList<>();
            for(int index=0;index<neededAssociations;index++) associationRows.add(new Object[]{demandIds.get(index/fixtureItemCount),itemIds.get(index%fixtureItemCount)});
            long overflowDemand=demandIds.get(activeDemandCount);
            associationRows.add(new Object[]{overflowDemand,itemIds.get(0)});
            new TransactionTemplate(transactions).executeWithoutResult(status->jdbc.batchUpdate("INSERT INTO cl_demand_item(demand_id,item_id) VALUES (?,?)",associationRows));
            IndependentMatchingInput atLimit=readOnlyMatchingSnapshot();
            assertEquals(IndependentMatchingSnapshotService.MAX_ACTIVE_ASSOCIATIONS,atLimit.demands().stream().mapToInt(demand->demand.offeredItemIds().size()).sum());
            assertFalse(atLimit.demands().stream().anyMatch(demand->demand.id()==overflowDemand));
            assertEquals(neededAssociations+1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_demand_item di JOIN cl_demand d ON d.id=di.demand_id WHERE d.owner_id=?",Integer.class,ownerId));
            jdbc.update("UPDATE cl_demand SET status='ACTIVE' WHERE id=?",overflowDemand);
            assertReadOnlySnapshotRejected(422);
            readOnlyIndependentMatches(token,422);
        } finally {deleteMatchingFixtureOwners(List.of(ownerId));}
        assertMatchingDomainRowsUnchanged(baselineRows);
    }

    @Test void independentMatchingHttpAdaptsTwoAndThreePartyFlowsSelectsOneDemandAndScopesTheViewer() throws Exception {
        List<Long> fixtureOwners=new ArrayList<>();
        try {
            String first=demandUser(),second=demandUser(),third=demandUser(),observer=demandUser();
            long firstId=matchingOwnerId(first),secondId=matchingOwnerId(second),thirdId=matchingOwnerId(third),observerId=matchingOwnerId(observer);
            fixtureOwners.addAll(List.of(firstId,secondId,thirdId,observerId));
            Map<Long,String> names=Map.of(firstId,"独立匹配甲",secondId,"独立匹配乙",thirdId,"独立匹配丙");
            demandCall("PATCH","/api/auth/me",first,Map.of("displayName",names.get(firstId)),200);
            demandCall("PATCH","/api/auth/me",second,Map.of("displayName",names.get(secondId)),200);
            demandCall("PATCH","/api/auth/me",third,Map.of("displayName",names.get(thirdId)),200);
            long firstItem=matchingGreenItem(first,List.of("usb","solar","green","small","light"),4);
            long secondItem=matchingGreenItem(second,List.of("leaf"),4),thirdItem=matchingGreenItem(third,List.of("portable"),1);
            long firstDemand=demandCall("POST","/api/demands",first,demandBody(6,"接收绿植甲",List.of("leaf"),List.of(firstItem)),200).path("id").asLong();
            long low=demandCall("POST","/api/demands",second,demandBody(6,"一个命中",List.of("green"),List.of(secondItem)),200).path("id").asLong();
            long best=demandCall("POST","/api/demands",second,demandBody(6,"最高分且较早",List.of("usb","solar"),List.of(secondItem)),200).path("id").asLong();
            long same=demandCall("POST","/api/demands",second,demandBody(6,"同分但较晚",List.of("solar","usb"),List.of(secondItem)),200).path("id").asLong();
            long disjoint=demandCall("POST","/api/demands",second,demandBody(6,"不同标签不得并集计分",List.of("small","light"),List.of(secondItem)),200).path("id").asLong();
            long thirdDemand=demandCall("POST","/api/demands",third,demandBody(6,"无偏好且低成色仍可参与",List.of(),List.of(thirdItem)),200).path("id").asLong();
            demandCall("POST","/api/demands",observer,demandBody(6,"无物品旁观者",List.of(),List.of()),200);
            JsonNode response=readOnlyIndependentMatches(first,200),recommendations=response.path("recommendations");
            assertEquals("independent-v2",response.path("ruleVersion").asText());
            boolean hasTwo=false,hasThree=false;
            for(JsonNode recommendation:recommendations) {
                hasTwo|=recommendation.path("length").asInt()==2;hasThree|=recommendation.path("length").asInt()==3;
                assertTrue(recommendation.path("id").asText().startsWith("independent-v2:"));
                assertEquals("independent-v2",recommendation.path("ruleVersion").asText());
                Set<Long> participantIds=matchingParticipantIds(recommendation);
                assertTrue(participantIds.contains(firstId));assertEquals(recommendation.path("length").asInt(),participantIds.size());
                for(JsonNode flow:recommendation.path("flows")) {
                    assertEquals(names.get(flow.path("fromUserId").asLong()),flow.path("fromName").asText());
                    assertEquals(names.get(flow.path("toUserId").asLong()),flow.path("toName").asText());
                    assertEquals(6,flow.path("matchedCategoryId").asInt());assertEquals("绿色植物",flow.path("matchedCategoryName").asText());
                }
            }
            assertTrue(hasTwo);assertTrue(hasThree);
            JsonNode pair=matchingRing(recommendations,Set.of(firstId,secondId));assertEquals(75,pair.path("score").asInt());
            for(JsonNode flow:pair.path("flows")) {
                if(flow.path("toUserId").asLong()==secondId) {
                    assertEquals(firstItem,flow.path("itemId").asLong());assertEquals(best,flow.path("demandId").asLong());
                    List<Long> matchedIds=new ArrayList<>();flow.path("matchedDemandIds").forEach(value->matchedIds.add(value.asLong()));
                    assertEquals(List.of(low,best,same,disjoint),matchedIds);
                    assertEquals(json.valueToTree(List.of("solar","usb")),flow.path("matchedTags"));
                } else {
                    assertEquals(firstId,flow.path("toUserId").asLong());assertEquals(secondItem,flow.path("itemId").asLong());
                    assertEquals(firstDemand,flow.path("demandId").asLong());assertEquals(json.valueToTree(List.of("leaf")),flow.path("matchedTags"));
                }
            }
            JsonNode secondView=readOnlyIndependentMatches(second,200).path("recommendations");
            assertEquals(pair.path("id"),matchingRing(secondView,Set.of(firstId,secondId)).path("id"));
            assertNotNull(matchingRing(secondView,Set.of(secondId,thirdId)));
            for(JsonNode recommendation:recommendations) assertNotEquals(Set.of(secondId,thirdId),matchingParticipantIds(recommendation));
            assertEquals(0,readOnlyIndependentMatches(observer,200).path("recommendations").size());
            assertTrue(readOnlyMatchingSnapshot().demands().stream().anyMatch(demand->demand.id()==thirdDemand));
        } finally {deleteMatchingFixtureOwners(fixtureOwners);}
    }
    @Test void independentMatchingHttpNeverFallsBackToLegacyWhenDemandIsInactiveDeletedOrUnlinked() throws Exception {
        List<Long> fixtureOwners=new ArrayList<>();
        try {
            String first=demandUser(),second=demandUser();long firstId=matchingOwnerId(first),secondId=matchingOwnerId(second);
            fixtureOwners.addAll(List.of(firstId,secondId));
            long firstItem=matchingGreenItem(first,List.of(),4),secondItem=matchingGreenItem(second,List.of(),4);
            demandCall("POST","/api/demands",first,demandBody(6,"需要独立绿植甲",List.of(),List.of(firstItem)),200);
            long secondDemand=demandCall("POST","/api/demands",second,demandBody(6,"需要独立绿植乙",List.of(),List.of(secondItem)),200).path("id").asLong();
            assertNotNull(matchingRing(readOnlyIndependentMatches(first,200).path("recommendations"),Set.of(firstId,secondId)));
            JsonNode legacy=readOnlyLegacyMatches(200);assertTrue(matchesContainItem(legacy,firstItem));assertTrue(matchesContainItem(legacy,secondItem));
            demandCall("PATCH","/api/demands/"+secondDemand+"/status",second,Map.of("version",0,"status","INACTIVE"),200);
            assertEquals(0,readOnlyIndependentMatches(first,200).path("recommendations").size());assertEquals(legacy,readOnlyLegacyMatches(200));
            demandCall("PATCH","/api/demands/"+secondDemand+"/status",second,Map.of("version",1,"status","ACTIVE"),200);
            assertNotNull(matchingRing(readOnlyIndependentMatches(first,200).path("recommendations"),Set.of(firstId,secondId)));
            demandCall("DELETE","/api/demands/"+secondDemand+"?version=2",second,null,200);
            assertEquals(0,readOnlyIndependentMatches(first,200).path("recommendations").size());assertEquals(legacy,readOnlyLegacyMatches(200));
            long unlinked=demandCall("POST","/api/demands",second,demandBody(6,"保存但尚未提供物品",List.of(),List.of()),200).path("id").asLong();
            assertEquals(0,readOnlyIndependentMatches(first,200).path("recommendations").size());
            demandCall("PATCH","/api/demands/"+unlinked,second,Map.of("version",0,"offeredItemIds",List.of(secondItem)),200);
            assertNotNull(matchingRing(readOnlyIndependentMatches(first,200).path("recommendations"),Set.of(firstId,secondId)));
            demandCall("PATCH","/api/demands/"+unlinked,second,Map.of("version",1,"offeredItemIds",List.of()),200);
            assertEquals(0,readOnlyIndependentMatches(first,200).path("recommendations").size());assertEquals(legacy,readOnlyLegacyMatches(200));
        } finally {deleteMatchingFixtureOwners(fixtureOwners);}
    }
    @Test void independentMatchingHttpRequiresAuthenticationAndRejectsVersionOrOwnerSpoofing() throws Exception {
        readOnlyIndependentMatches(null,401);
        String token=demandUser();long ownerId=matchingOwnerId(token);
        try {
            JsonNode defaultResult=readOnlyIndependentMatches(token,200);
            assertEquals("independent-v2",defaultResult.path("ruleVersion").asText());assertEquals(0,defaultResult.path("recommendations").size());
            assertEquals(defaultResult,readOnlyMatchingRequest("/api/matches/independent?ruleVersion=independent-v2",token,200));
            for(String query:List.of("ruleVersion=","ruleVersion=unknown","ruleVersion=legacy-v1","ownerId=1001","userId=1001","unknown=value",
                "ruleVersion=independent-v2&ownerId=1001","ruleVersion=independent-v2&ruleVersion=independent-v2"))
                readOnlyMatchingRequest("/api/matches/independent?"+query,token,400);
            JsonNode legacy=readOnlyLegacyMatches(200);
            assertTrue(legacy.isArray());assertEquals(legacy,readOnlyMatchingRequest("/api/matches?ruleVersion=legacy-v1",null,200));
            for(String version:List.of("independent-v2","unknown","")) readOnlyMatchingRequest("/api/matches?ruleVersion="+version,null,400);
        } finally {deleteMatchingFixtureOwners(List.of(ownerId));}
    }
    private long matchingGreenItem(String token,List<String> tags,int conditionLevel) throws Exception {
        long id=demandCall("POST","/api/items",token,Map.of("title","独立匹配绿植 "+UUID.randomUUID(),"description","隔离接口匹配夹具",
            "categoryId",6,"conditionLevel",conditionLevel,"tags",tags,"wantedCategoryId",6,"wantedTags",List.of()),200).path("id").asLong();
        jdbc.update("UPDATE cl_item SET status='AVAILABLE',review_basis='LEGACY_DIRECT' WHERE id=?",id);return id;
    }
    private Set<Long> matchingParticipantIds(JsonNode recommendation) {
        Set<Long> participants=new HashSet<>();for(JsonNode participant:recommendation.path("participants")) participants.add(participant.path("userId").asLong());return participants;
    }
    private JsonNode matchingRing(JsonNode recommendations,Set<Long> participantIds) {
        for(JsonNode recommendation:recommendations) if(matchingParticipantIds(recommendation).equals(participantIds)) return recommendation;
        fail("Expected a ring for the isolated fixture participants");return null;
    }
    private JsonNode readOnlyIndependentMatches(String token,int expectedStatus) throws Exception {
        return readOnlyMatchingRequest("/api/matches/independent",token,expectedStatus);
    }
    private JsonNode readOnlyMatchingRequest(String path,String token,int expectedStatus) throws Exception {
        Map<String,List<Map<String,Object>>> before=matchingDomainRows();
        JsonNode result=demandCall("GET",path,token,null,expectedStatus);assertMatchingDomainRowsUnchanged(before);return result;
    }
    private long matchingOwnerId(String token) throws Exception {
        return demandCall("GET","/api/auth/me",token,null,200).path("id").asLong();
    }
    private int eligibleMatchingItemCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cl_item i JOIN cl_user u ON u.id=i.owner_id "
            +"WHERE i.status='AVAILABLE' AND u.status='ACTIVE' AND NOT EXISTS (SELECT 1 FROM cl_item_hold h WHERE h.item_id=i.id)",Integer.class);
    }
    private long createMatchingHold(long ownerId,long itemId) {
        String key=UUID.randomUUID().toString();LocalDateTime expiry=LocalDateTime.now(ZoneOffset.UTC).plusHours(1);
        return new TransactionTemplate(transactions).execute(status->{
            jdbc.update("INSERT INTO cl_exchange(initiator_id,status,version,idempotency_key,expires_at) VALUES (?,'AWAITING_CONFIRMATION',0,?,?)",ownerId,key,expiry);
            long exchangeId=jdbc.queryForObject("SELECT id FROM cl_exchange WHERE initiator_id=? AND idempotency_key=?",Long.class,ownerId,key);
            jdbc.update("INSERT INTO cl_item_hold(item_id,exchange_id,expires_at) VALUES (?,?,?)",itemId,exchangeId,expiry);
            return exchangeId;
        });
    }
    private void deleteMatchingFixtureOwners(List<Long> ownerIds) {
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            for(long ownerId:ownerIds) jdbc.update("DELETE FROM cl_item_hold WHERE exchange_id IN (SELECT id FROM cl_exchange WHERE initiator_id=?)",ownerId);
            for(long ownerId:ownerIds) jdbc.update("DELETE FROM cl_exchange WHERE initiator_id=?",ownerId);
            for(long ownerId:ownerIds) jdbc.update("DELETE FROM cl_demand_item WHERE demand_id IN (SELECT id FROM cl_demand WHERE owner_id=?)",ownerId);
            for(long ownerId:ownerIds) jdbc.update("DELETE FROM cl_demand WHERE owner_id=?",ownerId);
            for(long ownerId:ownerIds) jdbc.update("DELETE FROM cl_item_review_audit WHERE item_id IN (SELECT id FROM cl_item WHERE owner_id=?)",ownerId);
            for(long ownerId:ownerIds) jdbc.update("DELETE FROM cl_item WHERE owner_id=?",ownerId);
            for(long ownerId:ownerIds) jdbc.update("DELETE FROM cl_auth_session WHERE user_id=?",ownerId);
            for(long ownerId:ownerIds) jdbc.update("DELETE FROM cl_user WHERE id=?",ownerId);
        });
    }
    private Map<String,List<Map<String,Object>>> matchingDomainRows() {
        Map<String,List<Map<String,Object>>> rows=new LinkedHashMap<>();
        rows.put("cl_item",jdbc.queryForList("SELECT * FROM cl_item ORDER BY id"));
        rows.put("cl_demand",jdbc.queryForList("SELECT * FROM cl_demand ORDER BY id"));
        rows.put("cl_demand_item",jdbc.queryForList("SELECT * FROM cl_demand_item ORDER BY demand_id,item_id"));
        rows.put("cl_exchange",jdbc.queryForList("SELECT * FROM cl_exchange ORDER BY id"));
        rows.put("cl_item_hold",jdbc.queryForList("SELECT * FROM cl_item_hold ORDER BY item_id"));
        return rows;
    }
    private void assertMatchingDomainRowsUnchanged(Map<String,List<Map<String,Object>>> expected) {
        Map<String,List<Map<String,Object>>> actual=matchingDomainRows();
        for(String table:expected.keySet()) {
            List<Map<String,Object>> before=expected.get(table),after=actual.get(table);assertEquals(before.size(),after.size(),table+" row count");
            for(int index=0;index<before.size();index++) assertEquals(before.get(index),after.get(index),table+" row "+index);
        }
    }
    private IndependentMatchingInput readOnlyMatchingSnapshot() {
        Map<String,List<Map<String,Object>>> before=matchingDomainRows();
        IndependentMatchingInput snapshot=independentSnapshots.snapshot();assertMatchingDomainRowsUnchanged(before);return snapshot;
    }
    private void assertReadOnlySnapshotRejected(int expectedStatus) {
        Map<String,List<Map<String,Object>>> before=matchingDomainRows();
        ApiException failure=assertThrows(ApiException.class,()->independentSnapshots.snapshot());
        assertEquals(expectedStatus,failure.getStatus());assertMatchingDomainRowsUnchanged(before);
    }
    private JsonNode readOnlyLegacyMatches(int expectedStatus) throws Exception {
        Map<String,List<Map<String,Object>>> before=matchingDomainRows();
        JsonNode matches=demandCall("GET","/api/matches",null,null,expectedStatus);assertMatchingDomainRowsUnchanged(before);return matches;
    }
    private boolean matchesContainItem(JsonNode matches,long itemId) {
        for(JsonNode match:matches) for(JsonNode participant:match.path("participants")) if(participant.path("itemId").asLong()==itemId) return true;
        return false;
    }
    private String demandUser() throws Exception {
        String username="test_"+UUID.randomUUID().toString().substring(0,12),password=UUID.randomUUID().toString();
        account(username,password,"USER");return login(username,password);
    }
    private long demandItem(String token) throws Exception {
        long id=demandCall("POST","/api/items",token,Map.of("title","需求关联测试 "+UUID.randomUUID(),"description","隔离测试的现有物品",
            "categoryId",1,"conditionLevel",4,"tags",List.of("教材"),"wantedCategoryId",2,"wantedTags",List.of("便携")),200).path("id").asLong();
        // Preserve explicit legacy AVAILABLE fixtures for existing domain/version tests.
        jdbc.update("UPDATE cl_item SET status='AVAILABLE',review_basis='LEGACY_DIRECT' WHERE id=?",id);return id;
    }
    private Map<String,Object> demandBody(long categoryId,String description,List<String> tags,List<Long> offeredItemIds) {
        return Map.of("categoryId",categoryId,"description",description,"preferredTags",tags,"offeredItemIds",offeredItemIds);
    }
    private JsonNode demandCall(String method,String path,String token,Object body,int expectedStatus) throws Exception {
        MockHttpServletRequestBuilder request=request(HttpMethod.valueOf(method),path);
        if(token!=null) request.header("Authorization","Bearer "+token);
        if(body!=null) request.contentType("application/json").content(json.writeValueAsString(body));
        MvcResult result=mvc.perform(request).andReturn();
        // Assert only safe status/code fields; never print authenticated request headers or whole responses.
        assertEquals(expectedStatus,result.getResponse().getStatus(),method+" "+path);
        JsonNode response=json.readTree(result.getResponse().getContentAsString());assertEquals(expectedStatus,response.path("code").asInt());
        return response.path("data");
    }
    @AfterAll void cleanUploads() throws Exception {try(var files=Files.list(UPLOADS)){for(Path file:files.toList())Files.delete(file);}Files.delete(UPLOADS);}
}
