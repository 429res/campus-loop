package edu.campusloop.web.notification.service;
import edu.campusloop.web.auth.service.MailDelivery;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
@Component public class NotificationMailWorker {
 private final JdbcTemplate db;private final MailDelivery mail;private final String site;
 public NotificationMailWorker(JdbcTemplate db,MailDelivery mail,@Value("${CAMPUS_PUBLIC_URL:https://amoewell.top}")String site){this.db=db;this.mail=mail;this.site=site;}
 @Scheduled(fixedDelayString="${CAMPUS_MAIL_POLL_MS:30000}",initialDelay=30000) public void deliver(){
  if(!mail.enabled())return;
  var rows=db.queryForList("SELECT o.id,n.title,n.body,n.link,u.email FROM cl_mail_outbox o JOIN cl_notification n ON n.id=o.notification_id JOIN cl_user u ON u.id=n.user_id WHERE n.kind='ACCOUNT_SECURITY' AND o.sent_at IS NULL AND o.attempts<5 AND o.next_attempt_at<=CURRENT_TIMESTAMP AND u.status='ACTIVE' AND u.email_verified=TRUE AND u.mail_notifications=TRUE ORDER BY o.id LIMIT 10");
  for(var r:rows){long id=((Number)r.get("id")).longValue();try{db.update("UPDATE cl_mail_outbox SET attempts=attempts+1,next_attempt_at=? WHERE id=?",java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(5),id);String link=String.valueOf(r.get("link"));mail.send(r.get("email").toString(),"Campus Loop · "+r.get("title"),r.get("body")+"\n\n"+(link.startsWith("/pages/")?site+"/#"+link:site)+"\n你可以在我的账号中关闭账户安全邮件提醒。");db.update("UPDATE cl_mail_outbox SET sent_at=CURRENT_TIMESTAMP WHERE id=?",id);}catch(edu.campusloop.common.ApiException ignored){/* Delivery failures remain queued; never log recipient or content. */}}
 }
}
