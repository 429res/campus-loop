package edu.campusloop;
import edu.campusloop.auth.*;
import edu.campusloop.common.ApiException;
import edu.campusloop.web.assistant.service.*;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.user.mapper.UserMapper;
import edu.campusloop.web.review.service.ItemReviewService;
import edu.campusloop.web.review.dto.ReviewDecisionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles(resolver=CampusIntegrationTest.Profile.class)
@Import(AutomaticReviewIntegrationTest.TestAI.class)
class AutomaticReviewIntegrationTest {
 @DynamicPropertySource static void config(DynamicPropertyRegistry r){boolean mysql=Boolean.getBoolean("campus.mysql-test");String url=mysql?System.getenv("TEST_DB_URL"):"jdbc:h2:mem:campus_auto_review_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";if(mysql&&(url==null||!url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/campus_loop_[a-z0-9_]*test(?:\\?.*)?")))throw new IllegalStateException("isolated test schema required");r.add("spring.datasource.url",()->url);r.add("spring.datasource.driver-class-name",()->mysql?"com.mysql.cj.jdbc.Driver":"org.h2.Driver");r.add("spring.datasource.username",()->mysql?System.getenv("TEST_DB_USERNAME"):"sa");r.add("spring.datasource.password",()->mysql?System.getenv("TEST_DB_PASSWORD"):"");r.add("campus.jwt-secret",()->"isolated-automatic-review-test-key-not-production");r.add("CAMPUS_AI_AUTO_REVIEW_ENABLED",()->false);}
 static class FakeAI extends QwenReviewService {
  final AtomicInteger calls=new AtomicInteger();volatile BiFunction<String,Map<String,Object>,Result> answer;
  FakeAI(){super(new ObjectMapper(),"","https://example.invalid","fixture-model",System.getProperty("java.io.tmpdir"));}
  public Result review(String type,Map<String,Object> snapshot){calls.incrementAndGet();return answer.apply(type,snapshot);}
 }
 @TestConfiguration static class TestAI {@Bean @Primary FakeAI fake(){return new FakeAI();}}
 @Autowired AutomaticReviewService queue;@Autowired FakeAI ai;@Autowired JdbcTemplate db;@Autowired UserMapper users;@Autowired ItemReviewService review;@Autowired AuthService auth;
 final Set<Long> owners=new HashSet<>();
 @BeforeEach void init(){ai.calls.set(0);ai.answer=(t,s)->result("APPROVE",0.98);}
 @AfterEach void cleanup(){for(long owner:owners)for(long exchange:db.queryForList("SELECT id FROM cl_exchange WHERE initiator_id=?",Long.class,owner)){db.update("DELETE FROM cl_item_hold WHERE exchange_id=?",exchange);db.update("DELETE FROM cl_exchange WHERE id=?",exchange);}for(long id:owners){db.update("UPDATE cl_community_report SET status='RESOLVED' WHERE post_id IN (SELECT id FROM cl_community_post WHERE author_id=?)",id);db.update("UPDATE cl_community_post SET status='DELETED' WHERE author_id=?",id);db.update("UPDATE cl_user SET status='DISABLED' WHERE id=?",id);}}
 static QwenReviewService.Result result(String decision,double confidence){return new QwenReviewService.Result(decision,confidence,"依据图文可以确定结论","fixture-model",List.of());}
 long user(){User u=new User();u.setUsername("auto_"+UUID.randomUUID().toString().replace("-",""));u.setDisplayName("虚构自动审核测试");u.setRole("USER");u.setStatus("ACTIVE");users.insert(u);owners.add(u.getId());return u.getId();}
 long item(){long owner=user();String title="auto_"+UUID.randomUUID();db.update("INSERT INTO cl_item(owner_id,title,description,category_id,condition_level,tags_json,wanted_category_id,wanted_tags_json,status) VALUES(?,?,?,1,4,'[]',2,'[]','PENDING_REVIEW')",owner,title,"一本旧教材，书页完整，有少量笔记");return db.queryForObject("SELECT id FROM cl_item WHERE title=?",Long.class,title);}
 long job(String type,long id){queue.discover();return db.queryForObject("SELECT MAX(id) FROM cl_ai_review WHERE target_type=? AND target_id=?",Long.class,type,id);}
 String state(long job){return db.queryForObject("SELECT state FROM cl_ai_review WHERE id=?",String.class,job);}
 String itemState(long id){return db.queryForObject("SELECT status FROM cl_item WHERE id=?",String.class,id);}
 @Test void approvalUsesSharedAuditAndNeverQueuesBusinessMail(){long id=item(),job=job("ITEM",id);queue.run(job);assertEquals("AVAILABLE",itemState(id));assertEquals("APPLIED",state(job));assertEquals("AI_REVIEW",db.queryForObject("SELECT review_basis FROM cl_item WHERE id=?",String.class,id));assertEquals("千问自动审核",db.queryForObject("SELECT operator_display_name FROM cl_item_review_audit WHERE item_id=?",String.class,id));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM cl_mail_outbox o JOIN cl_notification n ON n.id=o.notification_id JOIN cl_item i ON i.owner_id=n.user_id WHERE i.id=?",Integer.class,id));queue.run(job);assertEquals(1,ai.calls.get());}
 @Test void definiteViolationRejectedButUncertainOrFailedReviewStaysPending(){for(String decision:List.of("REJECT","REVIEW","LOW","FAIL","CHECKS")){long id=item(),job=job("ITEM",id);ai.answer=(t,s)->{if(decision.equals("FAIL"))throw new ApiException(503,"服务异常，转人工");if(decision.equals("CHECKS"))return new QwenReviewService.Result("APPROVE",0.99,"仍有未知","fixture-model",List.of("核实图片"));return result(decision.equals("LOW")?"APPROVE":decision,decision.equals("LOW")?0.4:0.99);};queue.run(job);assertEquals(decision.equals("REJECT")?"REJECTED":"PENDING_REVIEW",itemState(id));assertEquals(decision.equals("REJECT")?"APPLIED":"MANUAL",state(job));}}
 @Test void editedOrHumanReviewedContentCannotBeOverwritten(){for(boolean manual:List.of(false,true)){long id=item(),job=job("ITEM",id);ai.answer=(t,s)->{if(manual){long system=db.queryForObject("SELECT id FROM cl_user WHERE system_account=TRUE",Long.class);review.decide(system,id,new ReviewDecisionRequest(0,"REJECT","测试已作出的审核决定"));}else db.update("UPDATE cl_item SET description='用户已修改',version=version+1 WHERE id=?",id);return result("APPROVE",0.99);};queue.run(job);assertEquals(manual?"REJECTED":"PENDING_REVIEW",itemState(id));assertEquals("MANUAL",state(job));}}
 @Test void occupiedItemIsNeverAutomaticallyApproved(){long id=item(),job=job("ITEM",id);long owner=db.queryForObject("SELECT owner_id FROM cl_item WHERE id=?",Long.class,id);String key=UUID.randomUUID().toString();db.update("INSERT INTO cl_exchange(initiator_id,status,idempotency_key,expires_at) VALUES(?,'AWAITING_CONFIRMATION',?,CURRENT_TIMESTAMP)",owner,key);long exchange=db.queryForObject("SELECT id FROM cl_exchange WHERE idempotency_key=?",Long.class,key);db.update("INSERT INTO cl_item_hold(item_id,exchange_id,expires_at) VALUES(?,?,CURRENT_TIMESTAMP)",id,exchange);queue.run(job);assertEquals("MANUAL",state(job));assertEquals("PENDING_REVIEW",itemState(id));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM cl_item_hold WHERE item_id=?",Integer.class,id));}
 @Test void concurrentWorkersClaimOnlyOnce()throws Exception{long id=item(),job=job("ITEM",id);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);ai.answer=(t,s)->{entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return result("APPROVE",0.99);};var pool=Executors.newFixedThreadPool(2);try{var first=pool.submit(()->queue.run(job));assertTrue(entered.await(10,TimeUnit.SECONDS));var second=pool.submit(()->queue.run(job));second.get(10,TimeUnit.SECONDS);release.countDown();first.get(10,TimeUnit.SECONDS);assertEquals(1,ai.calls.get());assertEquals("APPLIED",state(job));}finally{release.countDown();pool.shutdownNow();}}
 @Test void itemReportRunsExistingTwoStageStateAndAudit(){long id=item();db.update("UPDATE cl_item SET status='AVAILABLE' WHERE id=?",id);long reporter=user();String key=UUID.randomUUID().toString();db.update("INSERT INTO cl_report(reporter_id,reporter_display_name,target_type,target_id,target_summary,reason,idempotency_key,request_digest,created_at) VALUES(?,'虚构举报人','ITEM',?,'虚构物品','疑似诈骗',?, ?,CURRENT_TIMESTAMP)",reporter,id,key,"a".repeat(64));long report=db.queryForObject("SELECT id FROM cl_report WHERE idempotency_key=?",Long.class,key);long job=job("REPORT",report);ai.answer=(t,s)->result("REJECT",0.98);queue.run(job);assertEquals("APPLIED",state(job));assertEquals("UPHELD",db.queryForObject("SELECT decision FROM cl_report WHERE id=?",String.class,report));assertEquals(2,db.queryForObject("SELECT version FROM cl_report WHERE id=?",Integer.class,report));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM cl_report_audit WHERE report_id=?",Integer.class,report));}
 @Test void communityReportCanHideOrDismissAndNewEvidencePreventsOldVerdict(){for(String action:List.of("REJECT","APPROVE","CHANGED")){long owner=user(),reporter=user();String key=UUID.randomUUID().toString();db.update("INSERT INTO cl_community_post(author_id,body,request_key,created_at) VALUES(?,'虚构测试动态',?,CURRENT_TIMESTAMP)",owner,key);long post=db.queryForObject("SELECT id FROM cl_community_post WHERE request_key=?",Long.class,key);db.update("INSERT INTO cl_community_report(post_id,reporter_id,reason,created_at) VALUES(?,?,'测试举报',CURRENT_TIMESTAMP)",post,reporter);long job=job("COMMUNITY",post);ai.answer=(t,s)->{if(action.equals("CHANGED"))db.update("INSERT INTO cl_community_report(post_id,reporter_id,reason,created_at) VALUES(?,?,'新增举报事实',CURRENT_TIMESTAMP)",post,user());return result(action.equals("REJECT")?"REJECT":"APPROVE",0.99);};queue.run(job);assertEquals(action.equals("CHANGED")?"MANUAL":"APPLIED",state(job));assertEquals(action.equals("REJECT")?"HIDDEN":"PUBLISHED",db.queryForObject("SELECT status FROM cl_community_post WHERE id=?",String.class,post));}}
 @Test void systemIdentityCannotLoginAndIsAbsentFromUserDirectory(){var system=users.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<User>().eq("system_account",true));assertNull(system.getPasswordHash());assertThrows(ApiException.class,()->auth.login(new edu.campusloop.web.auth.dto.LoginRequest(system.getUsername(),"invalid-test-password")));assertFalse(directory.page(1,100,null,"ADMIN",null).records().stream().anyMatch(u->u.id()==system.getId()));}
 @Autowired edu.campusloop.web.admin.service.AdminUserService directory;
}
