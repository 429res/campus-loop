package edu.campusloop.web.community.service;

import edu.campusloop.auth.AdminPermissions;
import edu.campusloop.common.*;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.user.mapper.UserMapper;
import edu.campusloop.web.upload.service.UploadReferenceService;
import edu.campusloop.web.notification.service.NotificationService;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class CommunityService {
 public record Publish(@NotBlank @Size(max=2000) String body,@Positive Long itemId,@Size(max=255) String imageUrl,@NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{8,64}") String requestKey){}
 public record ReplyRequest(@NotBlank @Size(max=1000) String body,@NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{8,64}") String requestKey,@Positive Long parentReplyId){}
 public record Version(@NotNull @Min(0) Integer version){}
 public record Reason(@NotBlank @Size(max=500) String reason){}
 public record Moderate(@NotNull @Min(0) Integer version,@NotNull @Pattern(regexp="HIDE|RESTORE|DISMISS_REPORTS") String action,@NotBlank @Size(max=500) String reason){}
 public record Post(long id,long authorId,String authorName,String avatarUrl,String body,Long itemId,String itemTitle,String itemImage,String imageUrl,String status,int version,long likes,long replies,boolean liked,long reports,LocalDateTime createdAt,List<Reply> previewReplies){}
 public record Reply(long id,long postId,long authorId,String authorName,String avatarUrl,String body,LocalDateTime createdAt,Long parentReplyId,String replyToName){}
 public record Report(long id,String reporterName,String reason,String status,LocalDateTime createdAt){}
 public record Audit(long id,String actorName,String action,String reason,LocalDateTime createdAt){}
 private record Row(long id,long authorId,String body,Long itemId,String imageUrl,String status,int version){}
 private final JdbcTemplate db;private final UserMapper users;private final UploadReferenceService uploads;private final NotificationService notifications;
 public CommunityService(JdbcTemplate db,UserMapper users,UploadReferenceService uploads,NotificationService notifications){this.db=db;this.users=users;this.uploads=uploads;this.notifications=notifications;}
 private static final String POST_SELECT="SELECT p.*,u.display_name,u.avatar_url,i.title AS item_title,i.image_url AS item_image,(SELECT COUNT(*) FROM cl_community_like l WHERE l.post_id=p.id) AS likes,(SELECT COUNT(*) FROM cl_community_reply r JOIN cl_user ru ON ru.id=r.author_id WHERE r.post_id=p.id AND r.status='PUBLISHED' AND ru.status='ACTIVE') AS replies,EXISTS(SELECT 1 FROM cl_community_like l WHERE l.post_id=p.id AND l.user_id=?) AS liked,(SELECT COUNT(*) FROM cl_community_report r WHERE r.post_id=p.id AND r.status='OPEN') AS reports FROM cl_community_post p JOIN cl_user u ON u.id=p.author_id LEFT JOIN cl_item i ON i.id=p.item_id AND i.status IN ('AVAILABLE','RESERVED','EXCHANGED') ";
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public PageResult<Post> page(Long viewer,int page,int size,boolean mine,boolean admin,boolean reported,String keyword){
  paging(page,size);if(mine&&viewer==null)throw new ApiException(401,"登录后查看自己的动态");
  if(keyword!=null&&keyword.length()>100)throw new ApiException(400,"搜索内容过长");
  String where=" WHERE "+(admin?"p.status<>'DELETED'":mine?"p.author_id=? AND p.status<>'DELETED'":"p.status='PUBLISHED' AND u.status='ACTIVE'");
  List<Object> args=new ArrayList<>();if(!admin&&mine)args.add(viewer);
  if(reported&&admin)where+=" AND EXISTS(SELECT 1 FROM cl_community_report r WHERE r.post_id=p.id AND r.status='OPEN')";
  if(keyword!=null&&!keyword.isBlank()){where+=" AND (p.body LIKE ? OR u.display_name LIKE ?)";args.add("%"+keyword.trim()+"%");args.add("%"+keyword.trim()+"%");}
  long count=db.queryForObject("SELECT COUNT(*) FROM cl_community_post p JOIN cl_user u ON u.id=p.author_id"+where,Long.class,args.toArray());
  List<Object> read=new ArrayList<>();read.add(viewer==null?0:viewer);read.addAll(args);read.add(size);read.add((long)(page-1)*size);
  var posts=db.query(POST_SELECT+where+" ORDER BY p.id DESC LIMIT ? OFFSET ?",(rs,n)->post(rs,admin),read.toArray());
  if(posts.isEmpty())return new PageResult<>(posts,count,page,size);
  String placeholders=String.join(",",Collections.nCopies(posts.size(),"?"));
  var previews=db.query("SELECT * FROM (SELECT r.*,u.display_name,u.avatar_url,ROW_NUMBER() OVER(PARTITION BY r.post_id ORDER BY r.id DESC) AS rn FROM cl_community_reply r JOIN cl_user u ON u.id=r.author_id WHERE r.status='PUBLISHED' AND u.status='ACTIVE' AND r.post_id IN ("+placeholders+")) ranked WHERE rn<=2 ORDER BY id ASC",(rs,n)->new Reply(rs.getLong("id"),rs.getLong("post_id"),rs.getLong("author_id"),rs.getString("display_name"),rs.getString("avatar_url"),rs.getString("body"),rs.getObject("created_at",LocalDateTime.class),rs.getObject("parent_reply_id",Long.class),null),posts.stream().map(Post::id).toArray());
  return new PageResult<>(posts.stream().map(p->new Post(p.id(),p.authorId(),p.authorName(),p.avatarUrl(),p.body(),p.itemId(),p.itemTitle(),p.itemImage(),p.imageUrl(),p.status(),p.version(),p.likes(),p.replies(),p.liked(),p.reports(),p.createdAt(),previews.stream().filter(r->r.postId()==p.id()).toList())).toList(),count,page,size);
 }
 @Transactional(readOnly=true) public Post detail(long id,Long viewer,boolean admin){
  var rows=db.query(POST_SELECT+" WHERE p.id=?",(rs,n)->post(rs,admin),viewer==null?0:viewer,id);
  if(rows.isEmpty())throw missing();var post=rows.get(0);
  if("DELETED".equals(post.status())||(!admin&&!(viewer!=null&&post.authorId()==viewer)&&(!"PUBLISHED".equals(post.status())||!active(post.authorId()))))throw missing();
  return post;
 }
 public Post publish(long actor,Publish request){
  lockActor(actor);String body=request.body().trim(),image=empty(request.imageUrl());
  var existing=db.query("SELECT id,body,item_id,image_url FROM cl_community_post WHERE author_id=? AND request_key=?",(rs,n)->new Row(rs.getLong("id"),actor,rs.getString("body"),rs.getObject("item_id",Long.class),rs.getString("image_url"),"",0),actor,request.requestKey());
  if(!existing.isEmpty()){var p=existing.get(0);if(!p.body().equals(body)||!Objects.equals(p.itemId(),request.itemId())||!Objects.equals(empty(p.imageUrl()),image))throw new ApiException(409,"请重新提交修改后的动态");return detail(p.id(),actor,false);}
  if(db.queryForObject("SELECT COUNT(*) FROM cl_community_post WHERE author_id=? AND created_at>?",Long.class,actor,now().minusDays(1))>=50)throw new ApiException(429,"今天发布得有些频繁，明天再来吧");
  if(request.itemId()!=null&&db.queryForObject("SELECT COUNT(*) FROM cl_item WHERE id=? AND owner_id=? AND status IN ('AVAILABLE','RESERVED','EXCHANGED')",Long.class,request.itemId(),actor)!=1)throw new ApiException(400,"只能关联自己已公开的物品");
  if(image!=null)uploads.publicImage(actor,image);
  db.update("INSERT INTO cl_community_post(author_id,body,item_id,image_url,status,version,request_key,created_at) VALUES(?,?,?,?,'PUBLISHED',0,?,?)",actor,body,request.itemId(),image,request.requestKey(),now());
  long id=db.queryForObject("SELECT id FROM cl_community_post WHERE author_id=? AND request_key=?",Long.class,actor,request.requestKey());return detail(id,actor,false);
 }
 public void withdraw(long actor,long id,int version){
  lockActor(actor);var p=lockPost(id);if(p.authorId()!=actor)throw new ApiException(403,"只能撤回自己的动态");
  if("DELETED".equals(p.status()))return;version(p,version);
  db.update("UPDATE cl_community_post SET status='DELETED',version=version+1 WHERE id=?",id);audit(id,actor,"WITHDRAW","作者撤回");
 }
 public void like(long actor,long id,boolean liked){
  lockActor(actor);var post=lockPost(id);published(post);
  boolean exists=db.queryForObject("SELECT COUNT(*) FROM cl_community_like WHERE post_id=? AND user_id=?",Long.class,id,actor)>0;
  if(liked&&!exists)db.update("INSERT INTO cl_community_like(post_id,user_id) VALUES(?,?)",id,actor);
  if(!liked&&exists)db.update("DELETE FROM cl_community_like WHERE post_id=? AND user_id=?",id,actor);
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public PageResult<Reply> replies(long id,Long actor,int page,int size,boolean admin){
  paging(page,size);detail(id,actor,admin);
  String from=" FROM cl_community_reply r JOIN cl_user u ON u.id=r.author_id LEFT JOIN cl_community_reply parent ON parent.id=r.parent_reply_id AND parent.status='PUBLISHED' LEFT JOIN cl_user recipient ON recipient.id=parent.author_id AND recipient.status='ACTIVE' WHERE r.post_id=? AND r.status='PUBLISHED' AND u.status='ACTIVE'";
  long total=db.queryForObject("SELECT COUNT(*)"+from,Long.class,id);
  var rows=db.query("SELECT r.*,u.display_name,u.avatar_url,recipient.display_name AS reply_to_name"+from+" ORDER BY r.id ASC LIMIT ? OFFSET ?",(rs,n)->new Reply(rs.getLong("id"),id,rs.getLong("author_id"),rs.getString("display_name"),rs.getString("avatar_url"),rs.getString("body"),rs.getObject("created_at",LocalDateTime.class),rs.getObject("parent_reply_id",Long.class),rs.getString("reply_to_name")),id,size,(long)(page-1)*size);
  return new PageResult<>(rows,total,page,size);
 }
 public long reply(long actor,long id,ReplyRequest body){
  lockParticipants(actor,id,body.parentReplyId());var post=lockPost(id);published(post);
  var previous=db.queryForList("SELECT id,post_id,body,parent_reply_id FROM cl_community_reply WHERE author_id=? AND request_key=?",actor,body.requestKey());
  if(!previous.isEmpty()){var p=previous.get(0);if(((Number)p.get("post_id")).longValue()!=id||!p.get("body").equals(body.body().trim())||!Objects.equals(p.get("parent_reply_id")==null?null:((Number)p.get("parent_reply_id")).longValue(),body.parentReplyId()))throw new ApiException(409,"回复内容已变化，请重新发送");return ((Number)p.get("id")).longValue();}
  Long recipient=null;
  if(body.parentReplyId()!=null){
   var targets=db.queryForList("SELECT r.author_id FROM cl_community_reply r JOIN cl_user u ON u.id=r.author_id WHERE r.id=? AND r.post_id=? AND r.status='PUBLISHED' AND u.status='ACTIVE'",Long.class,body.parentReplyId(),id);
   if(targets.isEmpty())throw new ApiException(404,"被回复的评论已不可见，请刷新后重试");recipient=targets.get(0);
  }
  if(db.queryForObject("SELECT COUNT(*) FROM cl_community_reply WHERE author_id=? AND created_at>?",Long.class,actor,now().minusMinutes(1))>=10)throw new ApiException(429,"回复得有些快，请稍后再试");
  db.update("INSERT INTO cl_community_reply(post_id,author_id,body,request_key,created_at,parent_reply_id) VALUES(?,?,?,?,?,?)",id,actor,body.body().trim(),body.requestKey(),now(),body.parentReplyId());
  long replyId=db.queryForObject("SELECT id FROM cl_community_reply WHERE author_id=? AND request_key=?",Long.class,actor,body.requestKey());
  if(actor!=post.authorId())notifications.send(post.authorId(),"COMMUNITY","有人回复了你的动态",body.body().trim(),"/pages/community/community?id="+id,"REPLY:"+replyId);
  if(recipient!=null&&recipient!=actor&&recipient!=post.authorId())notifications.send(recipient,"COMMUNITY","有人回复了你的评论",body.body().trim(),"/pages/community/community?id="+id,"REPLY:"+replyId);
  return replyId;
 }
 public void deleteReply(long actor,long id,long replyId){
  var user=lockActor(actor);lockPost(id);
  var authors=db.queryForList("SELECT author_id FROM cl_community_reply WHERE id=? AND post_id=?",Long.class,replyId,id);
  if(authors.isEmpty())throw new ApiException(404,"回复不存在");
  if(authors.get(0)!=actor)AdminPermissions.require(user,"COMMUNITY");
  db.update("UPDATE cl_community_reply SET status='DELETED' WHERE id=? AND post_id=?",replyId,id);audit(id,actor,"REPLY_DELETED","移除回复 #"+replyId);
 }
 public void report(long actor,long id,Reason body){
  lockActor(actor);var post=lockPost(id);published(post);
  if(post.authorId()==actor)throw new ApiException(400,"自己的动态可以直接撤回");
  if(db.queryForObject("SELECT COUNT(*) FROM cl_community_report WHERE post_id=? AND reporter_id=?",Long.class,id,actor)==0)
   db.update("INSERT INTO cl_community_report(post_id,reporter_id,reason,created_at) VALUES(?,?,?,?)",id,actor,body.reason().trim(),now());
 }
 public void moderate(long actor,long id,Moderate body){
  var user=lockParticipants(actor,id);AdminPermissions.require(user,"COMMUNITY");var post=lockPost(id);version(post,body.version());
  if("DELETED".equals(post.status()))throw new ApiException(409,"作者已撤回，不能恢复");
  String status=switch(body.action()){case "HIDE"->"HIDDEN";case "RESTORE"->"PUBLISHED";default->post.status();};
  db.update("UPDATE cl_community_post SET status=?,version=version+1 WHERE id=?",status,id);
  db.update("UPDATE cl_community_report SET status='RESOLVED' WHERE post_id=? AND status='OPEN'",id);
  audit(id,actor,body.action(),body.reason());
  if(!body.action().equals("DISMISS_REPORTS"))notifications.send(post.authorId(),"COMMUNITY","你的动态已有处理结果",body.reason().trim(),"/pages/community/community?id="+id,"COMMUNITY:"+id+":"+(post.version()+1));
 }
 @Transactional(readOnly=true) public List<Report> reports(long id){return db.query("SELECT r.*,u.display_name FROM cl_community_report r JOIN cl_user u ON u.id=r.reporter_id WHERE r.post_id=? ORDER BY r.id DESC LIMIT 100",(rs,n)->new Report(rs.getLong("id"),rs.getString("display_name"),rs.getString("reason"),rs.getString("status"),rs.getObject("created_at",LocalDateTime.class)),id);}
 @Transactional(readOnly=true) public List<Audit> audits(long id){return db.query("SELECT a.*,u.display_name FROM cl_community_audit a JOIN cl_user u ON u.id=a.actor_id WHERE a.post_id=? ORDER BY a.id DESC LIMIT 100",(rs,n)->new Audit(rs.getLong("id"),rs.getString("display_name"),rs.getString("action"),rs.getString("reason"),rs.getObject("created_at",LocalDateTime.class)),id);}
 private Post post(java.sql.ResultSet rs,boolean admin)throws java.sql.SQLException{return new Post(rs.getLong("id"),rs.getLong("author_id"),rs.getString("display_name"),rs.getString("avatar_url"),rs.getString("body"),rs.getObject("item_id",Long.class),rs.getString("item_title"),rs.getString("item_image"),rs.getString("image_url"),rs.getString("status"),rs.getInt("version"),rs.getLong("likes"),rs.getLong("replies"),rs.getBoolean("liked"),admin?rs.getLong("reports"):0,rs.getObject("created_at",LocalDateTime.class),List.of());}
 private User lockParticipants(long actor,long postId){return lockParticipants(actor,postId,null);}
 private User lockParticipants(long actor,long postId,Long parentReplyId){
  var authors=db.queryForList("SELECT author_id FROM cl_community_post WHERE id=?",Long.class,postId);if(authors.isEmpty())throw missing();
  var participants=new TreeSet<>(List.of(actor,authors.get(0)));
  if(parentReplyId!=null)participants.addAll(db.queryForList("SELECT author_id FROM cl_community_reply WHERE id=? AND post_id=?",Long.class,parentReplyId,postId));
  User result=null;for(long id:participants){var user=users.selectByIdForUpdate(id);if(id==actor)result=user;}
  if(result==null||!"ACTIVE".equals(result.getStatus()))throw new ApiException(401,"账号不可用");return result;
 }
 private User lockActor(long id){var u=users.selectByIdForUpdate(id);if(u==null||!"ACTIVE".equals(u.getStatus()))throw new ApiException(401,"账号不可用");return u;}
 private boolean active(long id){return db.queryForObject("SELECT COUNT(*) FROM cl_user WHERE id=? AND status='ACTIVE'",Long.class,id)==1;}
 private Row lockPost(long id){var rows=db.query("SELECT * FROM cl_community_post WHERE id=? FOR UPDATE",(rs,n)->new Row(rs.getLong("id"),rs.getLong("author_id"),rs.getString("body"),rs.getObject("item_id",Long.class),rs.getString("image_url"),rs.getString("status"),rs.getInt("version")),id);if(rows.isEmpty())throw missing();return rows.get(0);}
 private void published(Row p){if(!"PUBLISHED".equals(p.status())||!active(p.authorId()))throw missing();}
 private void version(Row p,int v){if(p.version()!=v||v==Integer.MAX_VALUE)throw new ApiException(409,"动态已更新，请刷新后重试");}
 private void paging(int page,int size){if(page<1||size<1||size>50)throw new ApiException(400,"分页参数不正确");}
 private String empty(String value){return value==null||value.isBlank()?null:value;}
 private ApiException missing(){return new ApiException(404,"这条动态暂不可见");}
 private LocalDateTime now(){return LocalDateTime.now(ZoneOffset.UTC);}
 private void audit(long post,long actor,String action,String reason){db.update("INSERT INTO cl_community_audit(post_id,actor_id,action,reason,created_at) VALUES(?,?,?,?,?)",post,actor,action,reason.trim(),now());}
}
