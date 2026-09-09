package edu.campusloop.web.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campusloop.common.ApiException;
import edu.campusloop.web.review.service.ItemReviewService;
import edu.campusloop.web.review.dto.ReviewDecisionRequest;
import edu.campusloop.web.community.service.CommunityService;
import edu.campusloop.web.report.service.ReportService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class AutomaticReviewService {
 private final JdbcTemplate db;private final ObjectMapper json;private final QwenReviewService ai;
 private final ItemReviewService items;private final CommunityService community;private final ReportService reports;
 private final TransactionTemplate tx;private final boolean enabled;
 public AutomaticReviewService(JdbcTemplate db,ObjectMapper json,QwenReviewService ai,ItemReviewService items,CommunityService community,ReportService reports,PlatformTransactionManager manager,@Value("${CAMPUS_AI_AUTO_REVIEW_ENABLED:false}")boolean enabled){
  this.db=db;this.json=json;this.ai=ai;this.items=items;this.community=community;this.reports=reports;this.enabled=enabled;
  tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
 }
 public Map<String,Object> queue(String type){
  String open=switch(type){case "ITEM"->"EXISTS(SELECT 1 FROM cl_item i WHERE i.id=a.target_id AND i.status='PENDING_REVIEW')";case "REPORT"->"EXISTS(SELECT 1 FROM cl_report r WHERE r.id=a.target_id AND r.status IN ('SUBMITTED','IN_REVIEW'))";default->"EXISTS(SELECT 1 FROM cl_community_report r WHERE r.post_id=a.target_id AND r.status='OPEN')";};
  return Map.of("enabled",enabled,"records",db.queryForList("SELECT a.id,a.target_id,a.state,a.reason,a.decision,a.confidence,a.model FROM cl_ai_review a WHERE a.target_type=? AND "+open+" AND NOT EXISTS(SELECT 1 FROM cl_ai_review newer WHERE newer.target_type=a.target_type AND newer.target_id=a.target_id AND newer.id>a.id) ORDER BY CASE WHEN a.state='MANUAL' THEN 0 ELSE 1 END,a.id LIMIT 30",type));
 }
 public Map<String,Object> status(String type,long id){
  var rows=db.queryForList("SELECT state,decision,confidence,reason,model,created_at,updated_at FROM cl_ai_review WHERE target_type=? AND target_id=? ORDER BY id DESC LIMIT 1",type,id);
  return Map.of("enabled",enabled,"latest",rows.isEmpty()?Map.of():rows.get(0));
 }
 @Scheduled(fixedDelayString="${campus.ai-review.poll-ms:10000}",initialDelayString="${campus.ai-review.initial-ms:20000}")
 public void tick(){
  if(!enabled)return;
  // No automatic retry after a crash: a human receives the expired job instead.
  db.update("UPDATE cl_ai_review SET state='MANUAL',reason='审核任务中断，请人工处理',updated_at=CURRENT_TIMESTAMP WHERE state='RUNNING' AND updated_at<?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(300)));
  discover();
  var jobs=db.queryForList("SELECT id FROM cl_ai_review WHERE state='READY' ORDER BY id LIMIT 3",Long.class);
  for(long job:jobs)run(job);
 }
 public void discover(){
  for(var type:List.of("ITEM","REPORT","COMMUNITY")){
   String query=switch(type){
    case "ITEM"->"SELECT i.id FROM cl_item i JOIN cl_user u ON u.id=i.owner_id WHERE i.status='PENDING_REVIEW' AND u.status='ACTIVE' AND NOT EXISTS(SELECT 1 FROM cl_ai_review a WHERE a.target_type='ITEM' AND a.target_id=i.id AND a.revision=CAST(i.version AS CHAR)) ORDER BY i.id LIMIT 30";
    case "REPORT"->"SELECT r.id FROM cl_report r WHERE r.status='SUBMITTED' AND NOT EXISTS(SELECT 1 FROM cl_ai_review a WHERE a.target_type='REPORT' AND a.target_id=r.id AND a.revision=CAST(r.version AS CHAR)) ORDER BY r.id LIMIT 30";
    default->"SELECT p.id FROM cl_community_post p WHERE p.status='PUBLISHED' AND EXISTS(SELECT 1 FROM cl_community_report r WHERE r.post_id=p.id AND r.status='OPEN') AND NOT EXISTS(SELECT 1 FROM cl_ai_review a WHERE a.target_type='COMMUNITY' AND a.target_id=p.id AND a.revision=CONCAT(p.version,':',(SELECT MAX(r.id) FROM cl_community_report r WHERE r.post_id=p.id AND r.status='OPEN'))) ORDER BY p.id LIMIT 30";
   };
   for(long id:db.queryForList(query,Long.class))try{
    Map<String,Object> snapshot=snapshot(type,id);String encoded=encode(snapshot);
    db.update("INSERT INTO cl_ai_review(target_type,target_id,revision,snapshot_json,snapshot_hash) VALUES(?,?,?,?,?)",type,id,revision(type,snapshot),encoded,hash(encoded));
   }catch(org.springframework.dao.DuplicateKeyException ignored){}catch(ApiException ignored){}
  }
 }
 public void run(long job){
  if(db.update("UPDATE cl_ai_review SET state='RUNNING',updated_at=CURRENT_TIMESTAMP WHERE id=? AND state='READY'",job)!=1)return;
  var row=db.queryForMap("SELECT * FROM cl_ai_review WHERE id=?",job);
  try{
   String type=(String)row.get("target_type");long id=((Number)row.get("target_id")).longValue();
   var snapshot=json.readValue((String)row.get("snapshot_json"),LinkedHashMap.class);
   QwenReviewService.Result result=ai.review(type,snapshot);
   if(!result.certain()) {finish(job,"MANUAL",result,"需人工核实："+result.reason()+(result.checks().isEmpty()?"":"；"+String.join("；",result.checks())));return;}
   tx.executeWithoutResult(ignored->{
    long actor=db.queryForObject("SELECT id FROM cl_user WHERE system_account=TRUE AND username='__campus_loop_ai_review__' AND status='ACTIVE'",Long.class);
    // Lock users before target rows, matching existing lifecycle services.
    var owners=new TreeSet<Long>();owners.add(actor);
    String table=type.equals("REPORT")?"cl_item":type.equals("ITEM")?"cl_item":"cl_community_post";
    long target=type.equals("REPORT")?((Number)((Map<?,?>)snapshot.get("report")).get("target_id")).longValue():id;
    String ownerColumn=type.equals("COMMUNITY")?"author_id":"owner_id";
    owners.add(db.queryForObject("SELECT "+ownerColumn+" FROM "+table+" WHERE id=?",Long.class,target));
    if(type.equals("REPORT"))owners.add(db.queryForObject("SELECT reporter_id FROM cl_report WHERE id=?",Long.class,id));
    for(long owner:owners){var user=db.queryForMap("SELECT status FROM cl_user WHERE id=? FOR UPDATE",owner);if(!"ACTIVE".equals(user.get("status")))throw new ApiException(409,"相关账号状态已改变，请人工处理");}
    db.queryForList("SELECT id FROM "+table+" WHERE id=? FOR UPDATE",target);
    if(type.equals("REPORT"))db.queryForList("SELECT id FROM cl_report WHERE id=? FOR UPDATE",id);
    else if(type.equals("COMMUNITY"))db.queryForList("SELECT id FROM cl_community_report WHERE post_id=? AND status='OPEN' ORDER BY id FOR UPDATE",id);
    var latest=snapshot(type,id);
    if(!row.get("snapshot_hash").equals(hash(encode(latest))))throw new ApiException(409,"内容或举报已变化，旧 AI 结果不会应用");
    String reason="AI 自动审核（"+result.model()+"）："+result.reason();if(reason.length()>490)reason=reason.substring(0,490);
    if(type.equals("ITEM"))items.decide(actor,id,new ReviewDecisionRequest(((Number)((Map<?,?>)latest.get("item")).get("version")).intValue(),result.decision(),reason));
    else if(type.equals("COMMUNITY"))community.moderate(actor,id,new CommunityService.Moderate(((Number)((Map<?,?>)latest.get("post")).get("version")).intValue(),result.decision().equals("REJECT")?"HIDE":"DISMISS_REPORTS",reason));
    else {
     // Exchange-related cases require human handling; automated reports never cancel/modify exchanges.
     String state=(String)((Map<?,?>)latest.get("item")).get("status");
     if(!"AVAILABLE".equals(state))throw new ApiException(409,"物品状态涉及交换或已变更，请人工处理举报");
     reports.decideAutomatically(actor,id,((Number)((Map<?,?>)latest.get("report")).get("version")).intValue(),result.decision().equals("REJECT")?"UPHELD":"DISMISSED",reason);
    }
    finish(job,"APPLIED",result,result.reason());
   });
  }catch(Exception e){String reason=e instanceof ApiException?e.getMessage():"AI 审核未能完成，请人工处理";db.update("UPDATE cl_ai_review SET state='MANUAL',reason=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND state='RUNNING'",reason,job);}
 }
 private void finish(long job,String state,QwenReviewService.Result result,String reason){
  if(reason.length()>1500)reason=reason.substring(0,1500);
  if(db.update("UPDATE cl_ai_review SET state=?,decision=?,confidence=?,reason=?,model=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND state='RUNNING'",state,result.decision(),Double.isFinite(result.confidence())?result.confidence():null,reason,result.model(),job)!=1)throw new ApiException(409,"审核任务已被接管");
 }
 private String revision(String type,Map<String,Object> snapshot){
  String key=type.equals("ITEM")?"item":type.equals("REPORT")?"report":"post";
  String version=((Map<?,?>)snapshot.get(key)).get("version").toString();
  if(type.equals("COMMUNITY")){var reports=(List<Map<String,Object>>)snapshot.get("reports");return version+":"+reports.get(reports.size()-1).get("id");}return version;
 }
 Map<String,Object> snapshot(String type,long id){
  Map<String,Object> out=new LinkedHashMap<>();List<String> images=new ArrayList<>();
  if(type.equals("COMMUNITY")){
   var post=db.queryForMap("SELECT body,image_url,status,version FROM cl_community_post WHERE id=?",id);
   var flags=db.queryForList("SELECT id,reason FROM cl_community_report WHERE post_id=? AND status='OPEN' ORDER BY id",id);
   if(!"PUBLISHED".equals(post.get("status"))||flags.isEmpty())throw new ApiException(409,"动态已处理或举报已变化");
   out.put("post",post);out.put("reports",flags);if(post.get("image_url")!=null)images.add((String)post.get("image_url"));
  }else{
   long itemId=id;
   if(type.equals("REPORT")){
    var report=db.queryForMap("SELECT target_id,reason,status,version FROM cl_report WHERE id=?",id);
    if(!"SUBMITTED".equals(report.get("status")))throw new ApiException(409,"举报已由管理员接管");
    out.put("report",report);itemId=((Number)report.get("target_id")).longValue();
    var evidence=db.queryForList("SELECT upload_id,content_hash FROM cl_report_evidence WHERE report_id=? ORDER BY position_no",id);out.put("evidence",evidence);
    for(var image:evidence)images.add("evidence:"+image.get("upload_id"));
   }
   var item=db.queryForMap("SELECT owner_id,title,description,category_id,condition_level,tags_json,image_url,image_urls_json,status,version FROM cl_item WHERE id=?",itemId);
   if(type.equals("ITEM")&&!"PENDING_REVIEW".equals(item.get("status")))throw new ApiException(409,"物品已处理");
   out.put("item",item);
   if(item.get("image_urls_json")!=null)try{for(var image:json.readTree((String)item.get("image_urls_json")))images.add(image.asText());}catch(Exception e){throw new ApiException(409,"图片记录异常，请人工处理");}
   if(item.get("image_url")!=null&&!images.contains(item.get("image_url")))images.add((String)item.get("image_url"));
  }
  out.put("images",images);return out;
 }
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
 private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
