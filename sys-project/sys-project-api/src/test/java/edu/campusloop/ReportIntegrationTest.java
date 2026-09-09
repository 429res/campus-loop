package edu.campusloop;

import com.fasterxml.jackson.databind.*;
import edu.campusloop.auth.PasswordService;
import edu.campusloop.common.ApiException;
import edu.campusloop.web.item.entity.Item;
import edu.campusloop.web.item.mapper.ItemMapper;
import edu.campusloop.web.report.dto.*;
import edu.campusloop.web.report.service.ReportService;
import edu.campusloop.web.upload.service.LocalUploadService;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.user.mapper.UserMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@org.springframework.context.annotation.Import(ReportIntegrationTest.ProbeConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles(resolver=CampusIntegrationTest.Profile.class)
class ReportIntegrationTest {
    private static final Path UPLOADS=tempUploads();
    private static Path tempUploads() {try{return Files.createTempDirectory("campus-report-test-");}catch(Exception e){throw new IllegalStateException(e);}}

    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry registry) {
        boolean mysql=Boolean.getBoolean("campus.mysql-test");
        String url=mysql?System.getenv("TEST_DB_URL"):"jdbc:h2:mem:campus_loop_report_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        String username=mysql?System.getenv("TEST_DB_USERNAME"):"sa",password=mysql?System.getenv("TEST_DB_PASSWORD"):"";
        if(mysql && (url==null || !url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/campus_loop_[a-z0-9_]*test(?:\\?.*)?")))
            throw new IllegalStateException("Report tests require an isolated localhost campus_loop_*test schema");
        if(username==null || password==null) throw new IllegalStateException("Explicit test credentials required");
        registry.add("spring.datasource.url",()->url);registry.add("spring.datasource.username",()->username);
        registry.add("spring.datasource.password",()->password);registry.add("spring.datasource.driver-class-name",()->mysql?"com.mysql.cj.jdbc.Driver":"org.h2.Driver");
        registry.add("spring.flyway.url",()->url);registry.add("spring.flyway.user",()->username);registry.add("spring.flyway.password",()->password);
        registry.add("campus.bootstrap-enabled",()->false);registry.add("campus.registration-mode",()->"DEVELOPMENT_SELF_SERVICE");
        registry.add("campus.jwt-secret",()->UUID.randomUUID().toString()+UUID.randomUUID());registry.add("campus.upload-dir",()->UPLOADS.toString());
        registry.add("campus.exchange-expiry.enabled",()->false);
    }

    @AfterAll static void cleanupUploads() throws Exception {remove(UPLOADS);remove(UPLOADS.resolveSibling(UPLOADS.getFileName()+"-evidence"));}
    private static void remove(Path directory) throws Exception {
        if(!Files.exists(directory)) return;
        try(var paths=Files.walk(directory)) {for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);}
    }

    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired UserMapper users;
    @Autowired ItemMapper items;@Autowired PasswordService passwords;@Autowired ReportService reports;@Autowired LocalUploadService uploads;
    Account reporter,other,adminOne,adminTwo;
    private final List<Long> createdUsers=new ArrayList<>();

    @BeforeEach void accounts() throws Exception {
        reporter=account("USER","提交同学");other=account("USER","目标同学");adminOne=account("ADMIN","陈管理员");adminTwo=account("ADMIN","林管理员");
    }

    @AfterEach void removeOnlyOwnFixtures() {
        for(long user:createdUsers) {
            for(long report:jdbc.queryForList("SELECT id FROM cl_report WHERE reporter_id=?",Long.class,user)) {
                jdbc.update("DELETE FROM cl_report_audit WHERE report_id=?",report);
                jdbc.update("DELETE FROM cl_report_evidence WHERE report_id=?",report);
                jdbc.update("DELETE FROM cl_report WHERE id=?",report);
            }
            for(long exchange:jdbc.queryForList("SELECT id FROM cl_exchange WHERE initiator_id=?",Long.class,user)) {
                jdbc.update("DELETE FROM cl_item_hold WHERE exchange_id=?",exchange);
                jdbc.update("DELETE FROM cl_exchange WHERE id=?",exchange);
            }
        }
        for(long user:createdUsers) {
            jdbc.update("DELETE FROM cl_upload WHERE owner_id=?",user);
            jdbc.update("DELETE FROM cl_item WHERE owner_id=?",user);
            jdbc.update("DELETE FROM cl_auth_session WHERE user_id=?",user);
        }
        for(long user:createdUsers) jdbc.update("DELETE FROM cl_user WHERE id=?",user);
    }

    @Test void realSubmissionAdminWorkflowMineReadbackAndAuditArePrivate() throws Exception {
        long target=item(other,"RESERVED");String exchangeKey="report-boundary-"+UUID.randomUUID();
        jdbc.update("INSERT INTO cl_exchange(initiator_id,status,version,idempotency_key,expires_at,created_at) VALUES(?,?,?,?,?,?)",
            other.id(),"DISPUTED",3,exchangeKey,LocalDateTime.now(ZoneOffset.UTC).plusHours(1),LocalDateTime.now(ZoneOffset.UTC));
        long exchange=jdbc.queryForObject("SELECT id FROM cl_exchange WHERE initiator_id=? AND idempotency_key=?",Long.class,other.id(),exchangeKey);
        jdbc.update("INSERT INTO cl_item_hold(item_id,exchange_id,expires_at) VALUES(?,?,?)",target,exchange,LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        Map<String,Object> itemBefore=jdbc.queryForMap("SELECT * FROM cl_item WHERE id=?",target);
        Map<String,Object> exchangeBefore=jdbc.queryForMap("SELECT * FROM cl_exchange WHERE id=?",exchange);
        Map<String,Object> holdBefore=jdbc.queryForMap("SELECT * FROM cl_item_hold WHERE item_id=?",target);
        String upload=upload(reporter);String reason="虚构举报理由：描述与现场情况不一致";
        Map<String,Object> submit=Map.of("targetType","ITEM","targetId",target,"reason",reason,"evidenceUploadIds",List.of(upload),"idempotencyKey","submit-"+UUID.randomUUID());

        call("POST","/api/reports",null,submit,401);JsonNode created=call("POST","/api/reports",reporter.token(),submit,200);
        long report=created.path("id").asLong(),evidence=created.at("/evidence/0/id").asLong();
        assertEquals("SUBMITTED",created.path("status").asText());assertEquals(0,created.path("version").asInt());
        assertEquals(reason,created.path("reason").asText());assertEquals("AVAILABLE",created.at("/evidence/0/accessStatus").asText());
        for(String privateField:List.of(upload,"uploadId","requestDigest","idempotencyKey","actorUserId")) assertFalse(created.toString().contains(privateField));

        JsonNode mine=call("GET","/api/reports/mine?status=SUBMITTED&targetType=ITEM&size=1",reporter.token(),null,200);
        assertEquals(1,mine.path("total").asInt());assertEquals(report,mine.at("/records/0/id").asLong());
        assertTrue(mine.at("/records/0/reason").isMissingNode());assertTrue(mine.at("/records/0/evidence").isMissingNode());
        call("GET","/api/reports/mine/"+report,other.token(),null,404);
        call("GET","/api/admin/reports",null,null,401);call("GET","/api/admin/reports",reporter.token(),null,403);
        JsonNode queue=call("GET","/api/admin/reports?keyword="+report+"&status=SUBMITTED&targetType=ITEM",adminOne.token(),null,200);
        assertEquals(1,queue.path("total").asInt());assertEquals("提交同学",queue.at("/records/0/reporterDisplayName").asText());
        assertFalse(queue.toString().contains(reason));assertTrue(queue.at("/records/0/evidence").isMissingNode());
        JsonNode detail=call("GET","/api/admin/reports/"+report,adminOne.token(),null,200);
        assertEquals(reason,detail.path("reason").asText());assertEquals(evidence,detail.at("/evidence/0/id").asLong());

        mvc.perform(get("/api/reports/mine/"+report+"/evidence/"+evidence+"/content").header("Authorization","Bearer "+other.token()))
            .andExpect(result->assertEquals(404,result.getResponse().getStatus()));
        MvcResult ownContent=mvc.perform(get("/api/reports/mine/"+report+"/evidence/"+evidence+"/content").header("Authorization","Bearer "+reporter.token())).andReturn();
        assertEquals(200,ownContent.getResponse().getStatus());assertEquals("image/png",ownContent.getResponse().getContentType());
        assertTrue(ownContent.getResponse().getContentAsByteArray().length>0);assertTrue(ownContent.getResponse().getHeader("Cache-Control").contains("no-store"));
        assertEquals(200,mvc.perform(get("/api/admin/reports/"+report+"/evidence/"+evidence+"/content").header("Authorization","Bearer "+adminOne.token())).andReturn().getResponse().getStatus());

        Map<String,Object> accept=Map.of("version",0,"reason","开始核对目标与授权证据");
        assertEquals("IN_REVIEW",call("POST","/api/admin/reports/"+report+"/accept",adminOne.token(),accept,200).path("status").asText());
        assertEquals(1,call("POST","/api/admin/reports/"+report+"/accept",adminOne.token(),accept,200).path("version").asInt());
        call("POST","/api/admin/reports/"+report+"/accept",adminTwo.token(),accept,409);
        Map<String,Object> decision=Map.of("version",1,"decision","UPHELD","reason","举报成立，仅记录治理结论");
        JsonNode resolved=call("POST","/api/admin/reports/"+report+"/decision",adminTwo.token(),decision,200);
        assertEquals("RESOLVED",resolved.path("status").asText());assertEquals(2,resolved.path("version").asInt());
        assertEquals("林管理员",resolved.at("/decidedBy/displayName").asText());
        assertEquals(resolved,call("POST","/api/admin/reports/"+report+"/decision",adminTwo.token(),decision,200));
        call("POST","/api/admin/reports/"+report+"/decision",adminOne.token(),decision,409);

        JsonNode mineResult=call("GET","/api/reports/mine/"+report,reporter.token(),null,200);
        assertEquals("UPHELD",mineResult.path("decision").asText());assertEquals("举报成立，仅记录治理结论",mineResult.path("decisionReason").asText());
        JsonNode audit=call("GET","/api/admin/reports/"+report+"/audits",adminOne.token(),null,200);
        assertEquals(3,audit.path("total").asInt());assertEquals("SUBMIT",audit.at("/records/0/action").asText());
        assertEquals("DECIDE",audit.at("/records/2/action").asText());
        for(String privateField:List.of("actorUserId","requestDigest","idempotencyKey","uploadId","password","token")) assertFalse(audit.toString().contains(privateField));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM cl_report_audit WHERE report_id=?",Integer.class,report));
        assertEquals(itemBefore,jdbc.queryForMap("SELECT * FROM cl_item WHERE id=?",target));
        assertEquals(exchangeBefore,jdbc.queryForMap("SELECT * FROM cl_exchange WHERE id=?",exchange));
        assertEquals(holdBefore,jdbc.queryForMap("SELECT * FROM cl_item_hold WHERE item_id=?",target));
    }

    @Test void invalidTargetForeignEvidenceAndForgedFieldsLeaveNoPartialReport() throws Exception {
        long available=item(other,"AVAILABLE"),hidden=item(other,"HIDDEN");String mine=upload(reporter),foreign=upload(other);
        long reportsBefore=count("cl_report"),evidenceBefore=count("cl_report_evidence"),auditsBefore=count("cl_report_audit");
        Map<String,Object> valid=new LinkedHashMap<>(Map.of("targetType","ITEM","targetId",available,"reason","虚构非法输入测试",
            "evidenceUploadIds",List.of(mine),"idempotencyKey","valid-"+UUID.randomUUID()));
        Map<String,Object> forged=new LinkedHashMap<>(valid);forged.put("reporterId",adminOne.id());call("POST","/api/reports",reporter.token(),forged,400);
        forged=new LinkedHashMap<>(valid);forged.put("status","RESOLVED");call("POST","/api/reports",reporter.token(),forged,400);
        Map<String,Object> wrongType=new LinkedHashMap<>(valid);wrongType.put("targetType","EXCHANGE");call("POST","/api/reports",reporter.token(),wrongType,400);
        Map<String,Object> missing=new LinkedHashMap<>(valid);missing.put("targetId",Long.MAX_VALUE);call("POST","/api/reports",reporter.token(),missing,404);
        Map<String,Object> invisible=new LinkedHashMap<>(valid);invisible.put("targetId",hidden);call("POST","/api/reports",reporter.token(),invisible,404);
        Map<String,Object> foreignEvidence=new LinkedHashMap<>(valid);foreignEvidence.put("evidenceUploadIds",List.of(mine,foreign));call("POST","/api/reports",reporter.token(),foreignEvidence,400);
        Map<String,Object> duplicateEvidence=new LinkedHashMap<>(valid);duplicateEvidence.put("evidenceUploadIds",List.of(mine,mine));call("POST","/api/reports",reporter.token(),duplicateEvidence,400);
        Map<String,Object> uppercaseKey=new LinkedHashMap<>(valid);uppercaseKey.put("idempotencyKey","Report-Key");call("POST","/api/reports",reporter.token(),uppercaseKey,400);
        Map<String,Object> blank=new LinkedHashMap<>(valid);blank.put("reason","   ");call("POST","/api/reports",reporter.token(),blank,400);
        assertEquals(reportsBefore,count("cl_report"));assertEquals(evidenceBefore,count("cl_report_evidence"));assertEquals(auditsBefore,count("cl_report_audit"));
    }

    @Test void idempotencyOpenDuplicatePaginationAndLostEvidenceHaveExplicitSemantics() throws Exception {
        long target=item(other,"AVAILABLE");String upload=upload(reporter),key="retry-"+UUID.randomUUID();
        CreateReportCommand command=new CreateReportCommand("ITEM",target,"同一请求重试",List.of(upload),key);
        long id=reports.create(reporter.id(),command).id();long rows=count("cl_report");
        assertEquals(id,reports.create(reporter.id(),command).id());assertEquals(rows,count("cl_report"));
        ApiException changed=assertThrows(ApiException.class,()->reports.create(reporter.id(),new CreateReportCommand("ITEM",target,"不同内容",List.of(upload),key)));
        assertEquals(409,changed.getStatus());
        assertEquals(409,assertThrows(ApiException.class,()->reports.create(reporter.id(),new CreateReportCommand("ITEM",target,"新逻辑请求",List.of(upload),"new-"+UUID.randomUUID()))).getStatus());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_report_audit WHERE report_id=?",Integer.class,id));
        jdbc.update("UPDATE cl_item SET status='HIDDEN',version=version+1 WHERE id=?",target);
        Files.write(uploads.evidencePath(upload),new byte[]{1,2,3},StandardOpenOption.TRUNCATE_EXISTING);
        JsonNode detail=call("GET","/api/reports/mine/"+id,reporter.token(),null,200);
        assertFalse(detail.path("targetAvailable").asBoolean());assertTrue(detail.path("targetSummary").asText().contains("ITEM #"+target));
        assertEquals("MISSING",detail.at("/evidence/0/accessStatus").asText());long evidence=detail.at("/evidence/0/id").asLong();
        assertEquals(404,mvc.perform(get("/api/reports/mine/"+id+"/evidence/"+evidence+"/content").header("Authorization","Bearer "+reporter.token())).andReturn().getResponse().getStatus());
        for(String query:List.of("page=0","size=101","status=FAKE","targetType=EXCHANGE","page=1&page=2","ownerId=1"))
            call("GET","/api/reports/mine?"+query,reporter.token(),null,400);
        MvcResult search=mvc.perform(get("/api/admin/reports").param("keyword",items.selectById(target).getTitle()).param("size","1")
            .header("Authorization","Bearer "+adminOne.token())).andReturn();
        assertEquals(200,search.getResponse().getStatus());assertEquals(1,json.readTree(search.getResponse().getContentAsString()).at("/data/total").asInt());
    }

    @Test void decisionWriteAndAuditAppendRollbackTogetherOnConstraintFailure() throws Exception {
        long target=item(other,"AVAILABLE");long report=reports.create(reporter.id(),new CreateReportCommand("ITEM",target,"回滚举报",
            List.of(),"rollback-"+UUID.randomUUID())).id();
        reports.accept(adminOne.id(),report,new AcceptReportCommand(0,"进入核对"));
        Map<String,Object> before=jdbc.queryForMap("SELECT * FROM cl_report WHERE id=?",report);long auditBefore=count("cl_report_audit");
        AtomicBoolean injected=new AtomicBoolean();
        SqlProbe.after.set(statement->{
            if(statement.endsWith("ReportMapper.decide") && injected.compareAndSet(false,true))
                jdbc.update("INSERT INTO cl_report_audit(report_id,actor_user_id,actor_display_name,action,previous_status,new_status,previous_version,new_version,reason,decision,request_digest,created_at) " +
                    "SELECT report_id,actor_user_id,actor_display_name,action,previous_status,new_status,previous_version,new_version,reason,decision,request_digest,created_at FROM cl_report_audit WHERE report_id=? AND action='ACCEPT'",report);
        });
        try {assertEquals(409,assertThrows(ApiException.class,()->reports.decide(adminOne.id(),report,new DecideReportCommand(1,"DISMISSED","故障注入"))).getStatus());}
        finally {SqlProbe.after.remove();}
        assertTrue(injected.get());assertEquals(before,jdbc.queryForMap("SELECT * FROM cl_report WHERE id=?",report));
        assertEquals(auditBefore,count("cl_report_audit"));
        reports.decide(adminOne.id(),report,new DecideReportCommand(1,"DISMISSED","恢复后完成"));
        assertEquals("RESOLVED",jdbc.queryForObject("SELECT status FROM cl_report WHERE id=?",String.class,report));
    }

    @Test void mysqlConcurrentSubmitAndTwoAdminDecisionUseRealRowLocks() throws Exception {
        mysqlOnly();long target=item(other,"AVAILABLE");String upload=upload(reporter);
        CreateReportCommand command=new CreateReportCommand("ITEM",target,"MySQL 并发提交",List.of(upload),"mysql-"+UUID.randomUUID());
        List<Long> retry=race(()->reports.create(reporter.id(),command).id(),()->reports.create(reporter.id(),command).id(),
            "ReportMapper.insert","UserMapper.selectByIdForUpdate");
        assertEquals(retry.get(0),retry.get(1));long report=retry.get(0);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_report WHERE reporter_id=? AND idempotency_key=?",Integer.class,reporter.id(),command.idempotencyKey()));
        reports.accept(adminOne.id(),report,new AcceptReportCommand(0,"并发决定前受理"));
        List<Long> decisions=race(()->{reports.decide(adminOne.id(),report,new DecideReportCommand(1,"UPHELD","管理员一决定"));return report;},
            ()->{reports.decide(adminTwo.id(),report,new DecideReportCommand(1,"DISMISSED","管理员二决定"));return report;},
            "ReportAuditMapper.insert","ReportMapper.selectForUpdate");
        assertEquals(List.of(report,-409L),decisions);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cl_report_audit WHERE report_id=? AND action='DECIDE'",Integer.class,report));
        assertEquals("UPHELD",jdbc.queryForObject("SELECT decision FROM cl_report WHERE id=?",String.class,report));
    }

    private long item(Account owner,String status) {
        Item row=new Item();row.setOwnerId(owner.id());row.setTitle("虚构物品 "+UUID.randomUUID());row.setDescription("仅用于举报集成测试");
        row.setCategoryId(1L);row.setConditionLevel(4);row.setTagsJson("[]");row.setWantedCategoryId(2L);row.setWantedTagsJson("[]");
        row.setStatus(status);row.setVersion(0);row.setReviewBasis("AVAILABLE".equals(status)||"RESERVED".equals(status)?"ADMIN_REVIEW":"UNREVIEWED");
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));items.insert(row);return row.getId();
    }

    private Account account(String role,String displayName) throws Exception {
        String username="report_"+UUID.randomUUID().toString().replace("-","").substring(0,14),password=UUID.randomUUID().toString();
        User row=new User();row.setUsername(username);row.setPasswordHash(passwords.encode(password));row.setDisplayName(displayName);
        row.setRole(role);row.setStatus("ACTIVE");row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));users.insert(row);createdUsers.add(row.getId());
        return new Account(row.getId(),login(username,password),displayName);
    }

    private String login(String username,String password) throws Exception {
        return call("POST","/api/auth/login",null,Map.of("username",username,"password",password),200).path("token").asText();
    }

    private String upload(Account actor) throws Exception {
        BufferedImage image=new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,0xEE6688);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);
        MockMultipartFile file=new MockMultipartFile("file","fictional.png","image/png",bytes.toByteArray());
        MvcResult result=mvc.perform(multipart("/api/uploads/evidence").file(file).header("Authorization","Bearer "+actor.token())).andReturn();
        assertEquals(200,result.getResponse().getStatus());return json.readTree(result.getResponse().getContentAsString()).at("/data/uploadId").asText();
    }

    private JsonNode call(String method,String path,String token,Object body,int expected) throws Exception {
        var request=request(HttpMethod.valueOf(method),path);if(token!=null) request.header("Authorization","Bearer "+token);
        if(body!=null) request.contentType("application/json").content(json.writeValueAsString(body));
        MvcResult result=mvc.perform(request).andReturn();assertEquals(expected,result.getResponse().getStatus(),method+" "+path+"\n"+result.getResponse().getContentAsString());
        JsonNode envelope=json.readTree(result.getResponse().getContentAsString());assertEquals(expected,envelope.path("code").asInt());return envelope.path("data");
    }

    private long count(String table) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    private void mysqlOnly() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("campus.mysql-test"),"Row-lock evidence requires isolated MySQL");
        try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {assertEquals("MySQL",connection.getMetaData().getDatabaseProductName());}
    }

    private List<Long> race(Callable<Long> first,Callable<Long> second,String pauseStatement,String waitStatement) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1),waiting=new CountDownLatch(1);
        try {
            Future<Long> one=pool.submit(()->{SqlProbe.before.set(id->{if(id.endsWith(pauseStatement)){locked.countDown();await(release);}});try{return result(first);}finally{SqlProbe.before.remove();}});
            await(locked);Future<Long> two=pool.submit(()->{SqlProbe.before.set(id->{if(id.endsWith(waitStatement))waiting.countDown();});try{return result(second);}finally{SqlProbe.before.remove();}});
            await(waiting);assertThrows(TimeoutException.class,()->two.get(250,TimeUnit.MILLISECONDS));release.countDown();
            return List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS));
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    private static long result(Callable<Long> action) throws Exception {try{return action.call();}catch(ApiException conflict){return -conflict.getStatus();}}
    private static void await(CountDownLatch latch) {try{assertTrue(latch.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    private record Account(long id,String token,String displayName) {}

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class ProbeConfiguration {@org.springframework.context.annotation.Bean SqlProbe sqlProbe(){return new SqlProbe();}}
    @org.apache.ibatis.plugin.Intercepts({
        @org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="update",args={org.apache.ibatis.mapping.MappedStatement.class,Object.class}),
        @org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="query",args={org.apache.ibatis.mapping.MappedStatement.class,Object.class,org.apache.ibatis.session.RowBounds.class,org.apache.ibatis.session.ResultHandler.class})
    })
    static class SqlProbe implements org.apache.ibatis.plugin.Interceptor {
        static final ThreadLocal<java.util.function.Consumer<String>> before=new ThreadLocal<>(),after=new ThreadLocal<>();
        @Override public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            String id=((org.apache.ibatis.mapping.MappedStatement)invocation.getArgs()[0]).getId();if(before.get()!=null)before.get().accept(id);
            Object result=invocation.proceed();try{if(after.get()!=null)after.get().accept(id);}
            catch(org.springframework.dao.DataAccessException failure){if(failure.getMostSpecificCause() instanceof java.sql.SQLException sql)throw sql;throw failure;}
            return result;
        }
    }
}
