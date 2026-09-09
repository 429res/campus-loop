package edu.campusloop;
import edu.campusloop.auth.*;
import edu.campusloop.web.auth.service.*;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpMethod;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
@SpringBootTest(properties={"campus.registration-mode=EMAIL_VERIFIED"})
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles(resolver=CampusIntegrationTest.Profile.class)
@Import(AccountCommunityUpgradeIntegrationTest.TestMail.class)
class AccountCommunityUpgradeIntegrationTest {
 @DynamicPropertySource static void config(DynamicPropertyRegistry r){boolean mysql=Boolean.getBoolean("campus.mysql-test");String url=mysql?System.getenv("TEST_DB_URL"):"jdbc:h2:mem:campus_account_upgrade_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";if(mysql&&(url==null||!url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/campus_loop_[a-z0-9_]*test(?:\\?.*)?")))throw new IllegalStateException("isolated test schema required");r.add("spring.datasource.url",()->url);r.add("spring.datasource.driver-class-name",()->mysql?"com.mysql.cj.jdbc.Driver":"org.h2.Driver");r.add("spring.datasource.username",()->mysql?System.getenv("TEST_DB_USERNAME"):"sa");r.add("spring.datasource.password",()->mysql?System.getenv("TEST_DB_PASSWORD"):"");r.add("campus.jwt-secret",()->"isolated-account-upgrade-test-key-not-production");r.add("campus.upload-dir",()->System.getProperty("java.io.tmpdir")+"/campus-account-upgrade-test");}
 static class CapturingMail extends MailDelivery {final Map<String,String> codes=new ConcurrentHashMap<>();CapturingMail(){super("",465,true,"","","");}public boolean enabled(){return true;}public void send(String to,String title,String body){var m=java.util.regex.Pattern.compile("[0-9]{6}").matcher(body);if(m.find())codes.put(to,m.group());}}
 @TestConfiguration static class TestMail {@Bean @Primary CapturingMail capture(){return new CapturingMail();}}
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired UserMapper users;@Autowired JdbcTemplate db;@Autowired PasswordService passwords;@Autowired AuthService auth;@Autowired CapturingMail mail;@Autowired LoginChallengeService challenges;
 final Set<Long> createdUsers=new HashSet<>();
 @AfterEach void hideFixtures(){
  // Remove only exchanges created by this test; later suites share the disposable MySQL schema.
  for(long actor:createdUsers)for(long exchange:db.queryForList("SELECT id FROM cl_exchange WHERE initiator_id=?",Long.class,actor)){
   for(String table:List.of("cl_item_history","cl_item_hold","cl_exchange_event","cl_exchange_demand","cl_exchange_participant"))db.update("DELETE FROM "+table+" WHERE exchange_id=?",exchange);
   db.update("DELETE FROM cl_exchange WHERE id=?",exchange);
  }
  // Keep this test's posts and items out of public feeds.
  for(long id:createdUsers)db.update("UPDATE cl_user SET status='DISABLED',mail_notifications=FALSE WHERE id=?",id);
 }
 final String password=UUID.randomUUID().toString();
 String unique(){return "upgrade_"+UUID.randomUUID().toString().replace("-","").substring(0,15);}
 JsonNode call(String method,String url,String token,Object body,int status)throws Exception{var request=request(HttpMethod.valueOf(method),url).contentType("application/json");if(token!=null)request.header("Authorization","Bearer "+token);if(body!=null)request.content(json.writeValueAsString(body));var response=mvc.perform(request).andReturn().getResponse();assertEquals(status,response.getStatus(),response.getContentAsString());return json.readTree(response.getContentAsString()).path("data");}
 User user(boolean legacy,String role){var u=new User();u.setUsername(unique());u.setDisplayName("虚构升级测试");u.setPasswordHash(passwords.encode(password));u.setRole(role);u.setStatus("ACTIVE");u.setLegacyExchangeAccess(legacy);u.setEmailVerified(false);u.setVersion(0);users.insert(u);createdUsers.add(u.getId());return u;}
 String token(User u){return auth.login(new edu.campusloop.web.auth.dto.LoginRequest(u.getUsername(),password)).token();}
 @Test void verifiedRegistrationEmailLoginAndCodeReplay()throws Exception{String email=unique()+"@example.invalid",name=unique();var fields=new HashMap<String,Object>(Map.of("username",name,"displayName","虚构邮箱同学","password",password,"email",email));call("POST","/api/auth/register",null,fields,400);call("POST","/api/auth/email-code",null,Map.of("email",email),200);fields.put("emailCode",mail.codes.get(email));var result=call("POST","/api/auth/register",null,fields,200);createdUsers.add(result.path("id").asLong());assertTrue(result.path("emailVerified").asBoolean());assertFalse(result.path("legacyExchangeAccess").asBoolean());var login=call("POST","/api/auth/login",null,Map.of("username",email.toUpperCase(Locale.ROOT),"password",password),200);assertEquals(result.path("id").asLong(),login.path("user").path("id").asLong());fields.put("username",unique());call("POST","/api/auth/register",null,fields,400);}
 @Test void wrongEmailAttemptsPersistAndCodesHaveCooldown()throws Exception{String email=unique()+"@example.invalid";call("POST","/api/auth/email-code",null,Map.of("email",email),200);call("POST","/api/auth/email-code",null,Map.of("email",email),429);String code=mail.codes.get(email);for(int i=0;i<5;i++)call("POST","/api/auth/register",null,Map.of("username",unique(),"displayName","虚构","password",password,"email",email,"emailCode",code.equals("000000")?"111111":"000000"),400);call("POST","/api/auth/register",null,Map.of("username",unique(),"displayName","虚构","password",password,"email",email,"emailCode",code),400);}
 @Test void unverifiedNewAccountCannotWriteButLegacyCanAndBindRequiresPassword()throws Exception{var newer=user(false,"USER");var old=user(true,"USER");String nt=token(newer),ot=token(old);call("GET","/api/community/posts",nt,null,200);var post=Map.of("body","虚构动态","requestKey",unique());call("POST","/api/community/posts",nt,post,403);long postId=call("POST","/api/community/posts",ot,post,200).path("id").asLong();call("POST","/api/community/posts/"+postId+"/replies",nt,Map.of("body","回复","requestKey",unique()),403);String email=unique()+"@example.invalid";call("POST","/api/account/email-code",nt,Map.of("email",email),200);String code=mail.codes.get(email);call("PUT","/api/account/email",nt,Map.of("email",email,"code",code,"password","incorrect-password"),400);assertTrue(call("PUT","/api/account/email",nt,Map.of("email",email,"code",code,"password",password),200).path("emailVerified").asBoolean());call("POST","/api/community/posts",nt,post,200);}
 @Test void ownerCanEditButOthersCannotAndHiddenPostStaysHidden()throws Exception{var a=user(true,"USER");var b=user(true,"USER");String at=token(a),bt=token(b);long id=call("POST","/api/community/posts",at,Map.of("body","原始虚构内容","requestKey",unique()),200).path("id").asLong();var edit=Map.of("version",0,"body","更新虚构内容");call("PATCH","/api/community/posts/"+id,bt,edit,403);assertEquals("更新虚构内容",call("PATCH","/api/community/posts/"+id,at,edit,200).path("body").asText());call("PATCH","/api/community/posts/"+id,at,edit,409);db.update("UPDATE cl_community_post SET status='HIDDEN' WHERE id=?",id);assertEquals("HIDDEN",call("PATCH","/api/community/posts/"+id,at,Map.of("version",1,"body","隐藏后编辑"),200).path("status").asText());call("GET","/api/community/posts/"+id,bt,null,404);}
 @Test void deletionRequiresPasswordRevokesSessionsAndCannotBeReenabled()throws Exception{var u=user(true,"USER");String t=token(u);call("DELETE","/api/account",t,Map.of("version",0,"password","incorrect","reason","测试"),400);call("DELETE","/api/account",t,Map.of("version",0,"password",password,"reason","虚构测试注销"),200);call("GET","/api/auth/me",t,null,401);var stored=users.selectById(u.getId());assertNull(stored.getPasswordHash());assertNotNull(stored.getDeletedAt());assertEquals("已注销用户",stored.getDisplayName());var admin=user(true,"ADMIN");call("PATCH","/api/admin/users/"+u.getId()+"/status",token(admin),Map.of("version",1,"status","ACTIVE","reason","不能恢复注销账号"),404);}
 @Test void captchaStartsAfterTwoFailuresAndIsOneUse()throws Exception{String account=unique(),ip="192.0.2.88";assertFalse(challenges.required(account,ip));challenges.failed(account,ip);assertFalse(challenges.required(account,ip));challenges.failed(account,ip);assertTrue(challenges.required(account,ip));var image=challenges.image(ip);assertTrue(image.image().startsWith("data:image/png;base64,"));assertThrows(edu.campusloop.common.ApiException.class,()->challenges.verify(image.id(),"wrong",ip));assertThrows(edu.campusloop.common.ApiException.class,()->challenges.verify(image.id(),"wrong",ip));challenges.success(account,ip);assertFalse(challenges.required(account,ip));}
 @Autowired edu.campusloop.web.upload.service.LocalUploadService uploads;
 @Autowired edu.campusloop.web.notification.service.NotificationService notifications;
 @Autowired edu.campusloop.web.notification.service.NotificationMailWorker mailWorker;
 @Autowired org.springframework.transaction.support.TransactionTemplate transaction;
 String image(User actor,int bytes)throws Exception{
  var bitmap=new java.awt.image.BufferedImage(8,12,java.awt.image.BufferedImage.TYPE_INT_RGB);
  var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(bitmap,"png",out);
  byte[] payload=bytes>out.size()?Arrays.copyOf(out.toByteArray(),bytes):out.toByteArray();
  return uploads.store(actor.getId(),new org.springframework.mock.web.MockMultipartFile("file","fixture.png","image/png",payload),false).getUrl();
 }
 Map<String,Object> itemFields(List<String> images){return new HashMap<>(Map.of("title","虚构多图物品 "+unique(),"description","仅限隔离测试","categoryId",1,"wantedCategoryId",2,"conditionLevel",4,"tags",List.of(),"wantedTags",List.of(),"imageUrls",images));}
 long item(User actor,List<String> images)throws Exception{return call("POST","/api/items",token(actor),itemFields(images),200).path("id").asLong();}
 void approve(User admin,long item)throws Exception{call("POST","/api/admin/items/"+item+"/review",token(admin),Map.of("version",0,"decision","APPROVE","reason","虚构测试"),200);}
 @Test void galleryPreservesOrderValidatesEveryOwnerAndAcceptsTenMegabytes()throws Exception{
  var a=user(true,"USER");var b=user(true,"USER");String at=token(a);
  String first=image(a,10*1024*1024),second=image(a,0),foreign=image(b,0);
  assertThrows(edu.campusloop.common.ApiException.class,()->image(a,10*1024*1024+1));
  call("POST","/api/items",at,itemFields(List.of(first,foreign)),400);
  long id=call("POST","/api/items",at,itemFields(List.of(first,second)),200).path("id").asLong();
  var saved=call("GET","/api/items/mine/"+id,at,null,200);assertEquals(first,saved.path("imageUrl").asText());assertEquals(List.of(first,second),json.convertValue(saved.path("imageUrls"),List.class));
  var edit=itemFields(List.of(second,first));edit.put("version",0);call("PUT","/api/items/"+id,at,edit,200);
  assertEquals(second,call("GET","/api/items/mine/"+id,at,null,200).path("imageUrl").asText());
  call("POST","/api/items",at,itemFields(java.util.stream.IntStream.range(0,10).mapToObj(i->first).toList()),400);
 }
 @Test void spotlightRequiresAdminAndAutomaticallyHidesUnavailableItems()throws Exception{
  var a=user(true,"USER");var admin=user(true,"ADMIN");long id=item(a,List.of());String at=token(a),manager=token(admin),path="/api/admin/items/"+id+"/spotlight";
  call("PUT",path,at,Map.of("enabled",true,"sortOrder",0),403);call("PUT",path,manager,Map.of("enabled",true,"sortOrder",0),409);approve(admin,id);
  call("PUT",path,manager,Map.of("enabled",true,"sortOrder",0),200);
  assertTrue(call("GET","/api/spotlights",null,null,200).findValuesAsText("title").stream().anyMatch(t->t.contains("虚构多图")));
  db.update("UPDATE cl_item SET status='EXCHANGED' WHERE id=?",id);
  for(var visible:call("GET","/api/spotlights",null,null,200))assertNotEquals(id,visible.path("id").asLong());
  call("PUT",path,manager,Map.of("enabled",false,"sortOrder",0),200);
 }
 @Test void onlySecurityMailIsQueuedAndWorkerRejectsOldBusinessRows()throws Exception{
  var a=user(true,"USER");String email=unique()+"@example.invalid";db.update("UPDATE cl_user SET email=?,email_verified=TRUE,mail_notifications=FALSE WHERE id=?",email,a.getId());
  transaction.executeWithoutResult(s->notifications.send(a.getId(),"ACCOUNT_SECURITY","虚构安全通知","邮件转发测试","/pages/notifications/notifications",unique()));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM cl_mail_outbox o JOIN cl_notification n ON n.id=o.notification_id WHERE n.user_id=?",Integer.class,a.getId()));
  db.update("UPDATE cl_user SET mail_notifications=TRUE WHERE id=?",a.getId());
  transaction.executeWithoutResult(s->notifications.send(a.getId(),"COMMUNITY_REPLY","虚构通知","验证码以外的通知","/pages/notifications/notifications",unique()));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM cl_mail_outbox o JOIN cl_notification n ON n.id=o.notification_id WHERE n.user_id=?",Integer.class,a.getId()));
  // Even an old or mistakenly inserted business row must never be delivered.
  db.update("INSERT INTO cl_mail_outbox(notification_id,next_attempt_at) SELECT id,CURRENT_TIMESTAMP FROM cl_notification WHERE user_id=? AND kind='COMMUNITY_REPLY'",a.getId());
  mailWorker.deliver();assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM cl_mail_outbox o JOIN cl_notification n ON n.id=o.notification_id WHERE n.user_id=? AND o.sent_at IS NOT NULL",Integer.class,a.getId()));
  transaction.executeWithoutResult(t->notifications.send(a.getId(),"ACCOUNT_SECURITY","密码已修改","账户安全测试","/pages/profile-settings/profile-settings",unique()));
  mailWorker.deliver();assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM cl_mail_outbox o JOIN cl_notification n ON n.id=o.notification_id WHERE n.user_id=? AND o.sent_at IS NOT NULL",Integer.class,a.getId()));
 }
 @Test void deletionRejectsActiveExchangeAndAdminCanDeleteOrdinaryAccount()throws Exception{
  var a=user(true,"USER");var b=user(true,"USER");var admin=user(true,"ADMIN");long first=item(a,List.of()),second=item(b,List.of());approve(admin,first);approve(admin,second);
  call("POST","/api/exchanges",token(a),Map.of("ruleVersion","direct-v1","idempotencyKey",unique(),"flows",List.of(Map.of("itemId",first,"itemVersion",1,"demandId",0,"demandVersion",0),Map.of("itemId",second,"itemVersion",1,"demandId",0,"demandVersion",0))),200);
  call("DELETE","/api/account",token(a),Map.of("version",0,"password",password,"reason","测试"),409);
  var spare=user(true,"USER");call("DELETE","/api/admin/users/"+spare.getId(),token(admin),Map.of("version",0,"reason","删除虚构测试账号"),200);assertNotNull(users.selectById(spare.getId()).getDeletedAt());
 }

 @Autowired AccountSecurityAlerts securityAlerts;
 @Test void newLoginAddressAndPasswordChangeCreateOnlySecurityNotifications()throws Exception{
  var a=user(true,"USER");db.update("UPDATE cl_user SET email=?,email_verified=TRUE,mail_notifications=TRUE WHERE id=?",unique()+"@example.invalid",a.getId());
  securityAlerts.login(a.getId(),"192.0.2.10");securityAlerts.login(a.getId(),"192.0.2.10");
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM cl_notification WHERE user_id=?",Integer.class,a.getId()));
  securityAlerts.login(a.getId(),"192.0.2.11");
  String t=token(a);var me=call("GET","/api/auth/me",t,null,200);assertFalse(me.has("lastLoginAddress"));
  call("POST","/api/auth/password",t,Map.of("currentPassword",password,"newPassword",UUID.randomUUID().toString()),200);
  call("GET","/api/auth/me",t,null,401);
  assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM cl_notification WHERE user_id=? AND kind='ACCOUNT_SECURITY'",Integer.class,a.getId()));
  assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM cl_mail_outbox o JOIN cl_notification n ON n.id=o.notification_id WHERE n.user_id=?",Integer.class,a.getId()));
 }
}
