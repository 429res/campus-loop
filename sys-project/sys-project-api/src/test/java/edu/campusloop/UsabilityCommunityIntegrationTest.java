package edu.campusloop;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

@SpringBootTest @AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles(resolver=CampusIntegrationTest.Profile.class)
class UsabilityCommunityIntegrationTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){
  boolean mysql=Boolean.getBoolean("campus.mysql-test");String url=mysql?System.getenv("TEST_DB_URL"):"jdbc:h2:mem:campus_loop_community_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
  if(mysql&&(url==null||!url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/campus_loop_[a-z0-9_]*test(?:\\?.*)?")))throw new IllegalStateException("Isolated test database required");
  String user=mysql?System.getenv("TEST_DB_USERNAME"):"sa",password=mysql?System.getenv("TEST_DB_PASSWORD"):"";
  r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->user);r.add("spring.datasource.password",()->password);r.add("spring.datasource.driver-class-name",()->mysql?"com.mysql.cj.jdbc.Driver":"org.h2.Driver");r.add("spring.flyway.url",()->url);r.add("spring.flyway.user",()->user);r.add("spring.flyway.password",()->password);r.add("campus.bootstrap-enabled",()->false);r.add("campus.registration-mode",()->"DEVELOPMENT_SELF_SERVICE");r.add("campus.jwt-secret",()->UUID.randomUUID().toString()+UUID.randomUUID());
 }
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;
 private record Account(long id,String token,String username,String password){}
 private final List<Long> ids=new ArrayList<>();private Account admin,a,b;
 @BeforeEach void setup()throws Exception{admin=account("ALL");a=account(null);b=account(null);}
 @AfterEach void cleanup(){
  for(long id:ids){for(String table:List.of("cl_community_like","cl_community_report","cl_community_audit","cl_community_reply"))db.update("DELETE FROM "+table+" WHERE post_id IN (SELECT id FROM cl_community_post WHERE author_id=?)",id);}
  for(long id:ids)db.update("DELETE FROM cl_community_post WHERE author_id=?",id);
  for(long id:ids)for(long exchange:db.queryForList("SELECT id FROM cl_exchange WHERE initiator_id=?",Long.class,id)){
   for(String table:List.of("cl_item_history","cl_item_hold","cl_exchange_event","cl_exchange_demand","cl_exchange_participant"))db.update("DELETE FROM "+table+" WHERE exchange_id=?",exchange);db.update("DELETE FROM cl_exchange WHERE id=?",exchange);
  }
  for(long id:ids){db.update("DELETE FROM cl_demand_item WHERE demand_id IN (SELECT id FROM cl_demand WHERE owner_id=?)",id);db.update("DELETE FROM cl_demand WHERE owner_id=?",id);db.update("DELETE FROM cl_item_review_audit WHERE item_id IN (SELECT id FROM cl_item WHERE owner_id=?)",id);db.update("DELETE FROM cl_item WHERE owner_id=?",id);}
  for(long id:ids){db.update("DELETE FROM cl_account_audit WHERE target_user_id=? OR actor_user_id=?",id,id);db.update("DELETE FROM cl_user_access_audit WHERE target_user_id=? OR actor_user_id=?",id,id);db.update("DELETE FROM cl_user_status_audit WHERE target_user_id=? OR operator_user_id=?",id,id);}
  for(long id:ids){db.update("DELETE FROM cl_notification WHERE user_id=?",id);db.update("DELETE FROM cl_auth_session WHERE user_id=?",id);db.update("DELETE FROM cl_user WHERE id=?",id);}
 }
 @Test void publishingCreatesMatchingWithoutAnExtraDemandForm()throws Exception{
  long first=item(a,1,2),second=item(b,2,1);var demand=call("GET","/api/demands",a.token(),null,200).at("/records/0");
  assertEquals(first,demand.path("sourceItemId").asLong());assertEquals(2,demand.path("categoryId").asInt());
  assertEquals(1,call("GET","/api/matches/readiness",a.token(),null,200).path("PENDING_REVIEW").asInt());
  assertEquals(0,call("GET","/api/matches/independent?ruleVersion=independent-v2",a.token(),null,200).path("recommendations").size());
  approve(first);approve(second);
  var plans=call("GET","/api/matches/independent?ruleVersion=independent-v2",a.token(),null,200).path("recommendations");assertTrue(plans.size()>0);
  JsonNode plan=null;for(var candidate:plans){var people=new HashSet<Long>();candidate.path("participants").forEach(p->people.add(p.path("userId").asLong()));if(people.equals(Set.of(a.id(),b.id())))plan=candidate;}assertNotNull(plan);assertEquals(2,plan.path("length").asInt());
  var flows=new ArrayList<Map<String,Object>>();for(var flow:plan.path("flows"))flows.add(Map.of("itemId",flow.path("itemId").asLong(),"itemVersion",1,"demandId",flow.path("demandId").asLong(),"demandVersion",flow.path("demandVersion").asInt()));
  var created=call("POST","/api/exchanges",a.token(),Map.of("ruleVersion","independent-v2","idempotencyKey",UUID.randomUUID().toString(),"flows",flows),200);assertTrue(created.path("id").asLong()>0);
  assertEquals("RESERVED",call("GET","/api/items/mine/"+first,a.token(),null,200).path("status").asText());
 }
 @Test void autoDemandTracksEditsAndCannotBeEditedIndependently()throws Exception{
  long id=item(a,1,2);var d=call("GET","/api/demands",a.token(),null,200).at("/records/0");long demand=d.path("id").asLong();
  call("PATCH","/api/demands/"+demand+"/status",a.token(),Map.of("version",0,"status","INACTIVE"),409);
  call("DELETE","/api/demands/"+demand+"?version=0",a.token(),null,409);
  var edited=fields(1,1);edited.put("version",0);call("PUT","/api/items/"+id,a.token(),edited,200);
  var after=call("GET","/api/demands/"+demand,a.token(),null,200);assertEquals(1,after.path("categoryId").asInt());assertEquals(1,after.path("version").asInt());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM cl_demand WHERE source_item_id=?",Integer.class,id));
 }

 @Test void withdrawnItemPausesAutomaticDemandAndResubmissionRestoresIt()throws Exception{
  long item=item(a,1,2);approve(item);
  var demand=call("GET","/api/demands",a.token(),null,200).at("/records/0");long id=demand.path("id").asLong();
  call("POST","/api/items/"+item+"/withdraw",a.token(),Map.of("version",1),200);
  assertEquals("INACTIVE",call("GET","/api/demands/"+id,a.token(),null,200).path("status").asText());
  assertEquals(0,call("GET","/api/demands?status=ACTIVE",a.token(),null,200).path("total").asInt());
  call("POST","/api/items/"+item+"/relist",a.token(),Map.of("version",2),200);
  assertEquals("ACTIVE",call("GET","/api/demands/"+id,a.token(),null,200).path("status").asText());
 }
 @Test void targetedRepliesValidateParentPreserveIdempotencyAndPreviewVisibility()throws Exception{
  long id=post(a),other=post(a);String path="/api/community/posts/"+id;
  long parent=call("POST",path+"/replies",b.token(),Map.of("body","这件物品多大？","requestKey",key()),200).asLong();
  var reply=Map.of("body","约三十厘米","requestKey",key(),"parentReplyId",parent);
  long child=call("POST",path+"/replies",a.token(),reply,200).asLong();
  assertEquals(child,call("POST",path+"/replies",a.token(),reply,200).asLong());
  call("POST",path+"/replies",a.token(),Map.of("body","约三十厘米","requestKey",reply.get("requestKey")),409);
  call("POST","/api/community/posts/"+other+"/replies",a.token(),Map.of("body","跨帖目标","requestKey",key(),"parentReplyId",parent),404);
  var comments=call("GET",path+"/replies",null,null,200);assertEquals(parent,comments.at("/records/1/parentReplyId").asLong());assertEquals("隔离验证",comments.at("/records/1/replyToName").asText());
  assertEquals(1,call("GET","/api/notifications",b.token(),null,200).path("total").asInt());
  var preview=call("GET","/api/community/posts?mine=true",a.token(),null,200).at("/records/1/previewReplies");assertEquals(2,preview.size());
  call("DELETE",path+"/replies/"+parent,b.token(),null,200);
  assertTrue(call("GET",path+"/replies",null,null,200).at("/records/0/replyToName").isNull());
  call("POST",path+"/replies",a.token(),Map.of("body","回复撤回评论","requestKey",key(),"parentReplyId",parent),404);
  db.update("UPDATE cl_user SET status='DISABLED' WHERE id=?",a.id());
  assertEquals(0,call("GET","/api/community/posts",null,null,200).path("total").asInt());
 }
 @Test void communityIsPublicIdempotentAndDoesNotAcceptOtherPeoplesItemsOrImages()throws Exception{
  long own=item(a,1,2);approve(own);
  call("POST","/api/community/posts",b.token(),Map.of("body","别人的物品","itemId",own,"requestKey",key()),400);
  call("POST","/api/community/posts",a.token(),Map.of("body","外部图片","imageUrl","https://example.invalid/a.png","requestKey",key()),400);
  String key=key();var body=Map.of("body","<script>alert(1)</script> 我想换书","itemId",own,"requestKey",key);
  long id=call("POST","/api/community/posts",a.token(),body,200).path("id").asLong();assertEquals(id,call("POST","/api/community/posts",a.token(),body,200).path("id").asLong());
  call("POST","/api/community/posts",a.token(),Map.of("body","不同的内容","requestKey",key),409);
  var post=call("GET","/api/community/posts/"+id,null,null,200);assertEquals(body.get("body"),post.path("body").asText());assertFalse(post.has("contact"));assertEquals(0,post.path("reports").asInt());
  call("GET","/api/community/posts?mine=true",null,null,401);call("GET","/api/community/posts?size=51",null,null,400);
  call("DELETE","/api/community/posts/"+id,b.token(),Map.of("version",0),403);
  call("DELETE","/api/community/posts/"+id,a.token(),Map.of("version",0),200);call("GET","/api/community/posts/"+id,null,null,404);
 }
 @Test void repliesLikesAndReportsHaveOwnershipAndModerationBoundaries()throws Exception{
  long id=post(a);String path="/api/community/posts/"+id;
  call("PUT",path+"/like",b.token(),null,200);call("PUT",path+"/like",b.token(),null,200);assertEquals(1,call("GET",path,b.token(),null,200).path("likes").asInt());
  var reply=Map.of("body","想了解尺寸","requestKey",key());long replyId=call("POST",path+"/replies",b.token(),reply,200).asLong();assertEquals(replyId,call("POST",path+"/replies",b.token(),reply,200).asLong());
  assertEquals(1,call("GET","/api/notifications",a.token(),null,200).path("total").asInt());
  call("DELETE",path+"/replies/"+replyId,a.token(),null,403);
  call("POST",path+"/reports",b.token(),Map.of("reason","需要核对内容"),200);call("POST",path+"/reports",b.token(),Map.of("reason","重复提交"),200);
  call("GET","/api/admin/community/posts",b.token(),null,403);
  var mods=call("GET","/api/admin/community/posts?reported=true",admin.token(),null,200);assertEquals(1,mods.path("total").asInt());assertEquals(1,mods.at("/records/0/reports").asInt());
  String moderation="/api/admin/community/posts/"+id+"/moderate";
  call("POST",moderation,admin.token(),Map.of("version",0,"action","HIDE","reason","暂时隐藏"),200);call("GET",path,b.token(),null,404);call("GET",path,a.token(),null,200);
  call("GET",path+"/replies",null,null,404);call("POST",path+"/replies",b.token(),Map.of("body","不可继续回复","requestKey",key()),404);
  call("POST",moderation,admin.token(),Map.of("version",0,"action","RESTORE","reason","旧版本"),409);
  call("POST",moderation,admin.token(),Map.of("version",1,"action","RESTORE","reason","核对完成"),200);call("GET",path,null,null,200);
  call("DELETE",path+"/replies/"+replyId,admin.token(),null,200);assertEquals(0,call("GET",path+"/replies",null,null,200).path("total").asInt());
  call("DELETE",path+"/like",b.token(),null,200);assertEquals(0,call("GET",path,null,null,200).path("likes").asInt());
  assertEquals(0,call("GET","/api/admin/community/posts?reported=true",admin.token(),null,200).path("total").asInt());
 }
 @Test void concurrentReactionsAndRepliesRemainSingleAndCrossRepliesDoNotDeadlock()throws Exception{
  long first=post(a),second=post(b);String path="/api/community/posts/"+first;
  assertEquals(List.of(200,200),race(()->raw("PUT",path+"/like",b.token(),null),()->raw("PUT",path+"/like",b.token(),null)));
  assertEquals(1,call("GET",path,null,null,200).path("likes").asInt());
  var reply=Map.of("body","仅一条回复","requestKey",key());assertEquals(List.of(200,200),race(()->raw("POST",path+"/replies",b.token(),reply),()->raw("POST",path+"/replies",b.token(),reply)));
  assertEquals(1,call("GET",path+"/replies",null,null,200).path("total").asInt());
  assertEquals(List.of(200,200),race(()->raw("POST",path+"/replies",b.token(),Map.of("body","交叉回复 B","requestKey",key())),()->raw("POST","/api/community/posts/"+second+"/replies",a.token(),Map.of("body","交叉回复 A","requestKey",key()))));
 }
 @Test void accountManagementCreatesEditsResetsAndAuditsWithRestrictedAdminBoundaries()throws Exception{
  Account manager=account("USERS");String username="managed_"+key(),password=UUID.randomUUID().toString();
  var create=Map.of("username",username,"password",password,"displayName","新同学","role","USER","permissions",List.of(),"reason","添加账号");
  long id=call("POST","/api/admin/users",manager.token(),create,200).path("id").asLong();ids.add(id);
  call("POST","/api/admin/users",manager.token(),create,409);
  call("POST","/api/admin/users",manager.token(),Map.of("username","admin_"+key(),"password",password,"displayName","不可提升","role","ADMIN","permissions",List.of("ALL"),"reason","无权限"),403);
  call("PUT","/api/admin/users/"+admin.id()+"/profile",manager.token(),Map.of("version",0,"displayName","不可修改","reason","无权限"),403);
  call("PATCH","/api/admin/users/"+admin.id()+"/status",manager.token(),Map.of("version",0,"status","DISABLED","reason","无权限"),403);
  var edit=Map.of("version",0,"displayName","小林","campus","东校区","bio","喜欢阅读","reason","核对资料");
  var saved=call("PUT","/api/admin/users/"+id+"/profile",manager.token(),edit,200);assertEquals(1,saved.path("version").asInt());assertEquals("小林",saved.path("displayName").asText());assertFalse(saved.has("passwordHash"));
  call("PUT","/api/admin/users/"+id+"/profile",manager.token(),edit,409);
  String token=call("POST","/api/auth/login",null,Map.of("username",username,"password",password),200).path("token").asText();String next=UUID.randomUUID().toString();
  var reset=Map.of("version",1,"password",next,"reason","账号恢复");call("POST","/api/admin/users/"+id+"/password",manager.token(),reset,403);call("POST","/api/admin/users/"+id+"/password",admin.token(),reset,200);
  call("GET","/api/auth/me",token,null,401);call("POST","/api/auth/login",null,Map.of("username",username,"password",next),200);
  var log=call("GET","/api/admin/users/"+id+"/activity",admin.token(),null,200);assertEquals(3,log.path("total").asInt());assertFalse(log.toString().contains(next));assertTrue(log.findValuesAsText("action").contains("PASSWORD_RESET"));
 }
 private Account account(String scope)throws Exception{String name="usability_"+key(),password=UUID.randomUUID().toString();long id=call("POST","/api/auth/register",null,Map.of("username",name,"password",password,"displayName","隔离验证"),200).path("id").asLong();ids.add(id);if(scope!=null)db.update("UPDATE cl_user SET role='ADMIN',admin_permissions=? WHERE id=?",scope,id);String token=call("POST","/api/auth/login",null,Map.of("username",name,"password",password),200).path("token").asText();return new Account(id,token,name,password);}
 private LinkedHashMap<String,Object> fields(int category,int wanted){return new LinkedHashMap<>(Map.of("title","校园好物 "+key(),"description","功能验证物品","categoryId",category,"wantedCategoryId",wanted,"conditionLevel",4,"tags",List.of(),"wantedTags",List.of()));}
 private long item(Account actor,int category,int wanted)throws Exception{return call("POST","/api/items",actor.token(),fields(category,wanted),200).path("id").asLong();}
 private void approve(long id)throws Exception{call("POST","/api/admin/items/"+id+"/review",admin.token(),Map.of("version",0,"decision","APPROVE","reason","核对通过"),200);}
 private long post(Account actor)throws Exception{return call("POST","/api/community/posts",actor.token(),Map.of("body","校园分享 "+key(),"requestKey",key()),200).path("id").asLong();}
 private String key(){return UUID.randomUUID().toString();}
 private List<Integer> race(Callable<Integer> a,Callable<Integer> b)throws Exception{var pool=Executors.newFixedThreadPool(2);var latch=new CountDownLatch(1);try{var first=pool.submit(()->{latch.await();return a.call();});var second=pool.submit(()->{latch.await();return b.call();});latch.countDown();var result=new ArrayList<>(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS)));Collections.sort(result);return result;}finally{pool.shutdownNow();}}
 private MockHttpServletRequestBuilder builder(String method,String path,String token,Object body)throws Exception{var req=request(HttpMethod.valueOf(method),path);if(token!=null)req.header("Authorization","Bearer "+token);if(body!=null)req.contentType("application/json").content(json.writeValueAsString(body));return req;}
 private int raw(String method,String path,String token,Object body)throws Exception{return mvc.perform(builder(method,path,token,body)).andReturn().getResponse().getStatus();}
 private JsonNode call(String method,String path,String token,Object body,int expected)throws Exception{var response=mvc.perform(builder(method,path,token,body)).andReturn().getResponse();assertEquals(expected,response.getStatus(),method+" "+path);return json.readTree(response.getContentAsString()).path("data");}
}
