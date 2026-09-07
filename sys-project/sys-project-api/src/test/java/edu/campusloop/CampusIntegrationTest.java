package edu.campusloop;
import edu.campusloop.auth.PasswordService;
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
        registry.add("campus.jwt-secret",()->UUID.randomUUID().toString()+UUID.randomUUID());
        registry.add("campus.upload-dir",()->UPLOADS.toString());
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired UserMapper users;
    @Autowired PasswordService passwords;@Autowired JdbcTemplate jdbc;@Autowired DataSource ds;
    String memberToken,adminToken; long memberId; String memberName,memberPassword;
    @BeforeAll void setup() throws Exception {
        new ResourceDatabasePopulator(new ClassPathResource("db/demo-data.sql")).execute(ds);
        memberName="test_"+UUID.randomUUID().toString().substring(0,12);memberPassword=UUID.randomUUID().toString();
        memberId=account(memberName,memberPassword,"USER");
        String adminName="test_"+UUID.randomUUID().toString().substring(0,12), adminPassword=UUID.randomUUID().toString();
        account(adminName,adminPassword,"ADMIN");memberToken=login(memberName,memberPassword);adminToken=login(adminName,adminPassword);
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
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("AVAILABLE")).andReturn().getResponse().getContentAsString());
        long id=created.at("/data/id").asLong();
        assertEquals(title,jdbc.queryForObject("SELECT title FROM cl_item WHERE id=?",String.class,id));
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
        mvc.perform(post("/api/exchanges").header("Authorization","Bearer "+memberToken)).andExpect(status().isNotImplemented()).andExpect(jsonPath("$.code").value(501));
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
    @AfterAll void cleanUploads() throws Exception {try(var files=Files.list(UPLOADS)){for(Path file:files.toList())Files.delete(file);}Files.delete(UPLOADS);}
}
