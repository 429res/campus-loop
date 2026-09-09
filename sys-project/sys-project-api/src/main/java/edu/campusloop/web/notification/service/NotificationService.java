package edu.campusloop.web.notification.service;
import edu.campusloop.common.*;
import edu.campusloop.web.notification.entity.Notification;
import edu.campusloop.web.notification.mapper.NotificationMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
@Service
public class NotificationService {
 private final NotificationMapper messages;
 public NotificationService(NotificationMapper messages){this.messages=messages;}
 @Transactional(propagation=Propagation.MANDATORY)
 public void send(long user,String kind,String title,String body,String link,String sourceKey){
  var n=new Notification();n.setUserId(user);n.setKind(kind);n.setTitle(title);n.setBody(body==null?"":body);
  n.setLink(link);n.setSourceKey(sourceKey);n.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));messages.insert(n);
 }
 public PageResult<Notification> page(long user,int page,int size,boolean unread){
  if(page<1||size<1||size>100)throw new ApiException(400,"分页参数不正确");
  var q=new QueryWrapper<Notification>().eq("user_id",user);if(unread)q.isNull("read_at");q.orderByDesc("created_at","id");
  var result=messages.selectPage(new Page<Notification>(page,size),q);
  return new PageResult<>(result.getRecords(),result.getTotal(),page,size);
 }
 public long unread(long user){return messages.selectCount(new QueryWrapper<Notification>().eq("user_id",user).isNull("read_at"));}
 @Transactional public void read(long user,long id){
  var n=messages.selectOne(new QueryWrapper<Notification>().eq("id",id).eq("user_id",user));
  if(n==null)throw new ApiException(404,"消息不存在");
  messages.update(null,new UpdateWrapper<Notification>().eq("id",id).eq("user_id",user).isNull("read_at").set("read_at",LocalDateTime.now(ZoneOffset.UTC)));
 }
 @Transactional public void readAll(long user){messages.update(null,new UpdateWrapper<Notification>().eq("user_id",user).isNull("read_at").set("read_at",LocalDateTime.now(ZoneOffset.UTC)));}
}
