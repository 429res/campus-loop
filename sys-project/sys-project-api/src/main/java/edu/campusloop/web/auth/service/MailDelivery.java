package edu.campusloop.web.auth.service;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.SimpleMailMessage;
import edu.campusloop.common.ApiException;
@Service public class MailDelivery {
 private final JavaMailSenderImpl sender=new JavaMailSenderImpl();private final String from;
 public MailDelivery(@Value("${SMTP_HOST:}")String host,@Value("${SMTP_PORT:465}")int port,@Value("${SMTP_SSL:true}")boolean ssl,@Value("${SMTP_USERNAME:}")String username,@Value("${SMTP_PASSWORD:}")String password,@Value("${MAIL_FROM:}")String from){
  this.from=from;sender.setHost(host);sender.setPort(port);sender.setUsername(username);sender.setPassword(password);sender.setDefaultEncoding("UTF-8");
  var p=sender.getJavaMailProperties();p.setProperty("mail.smtp.auth","true");p.setProperty("mail.smtp.ssl.enable",String.valueOf(ssl));p.setProperty("mail.smtp.starttls.enable",String.valueOf(!ssl));p.setProperty("mail.smtp.starttls.required",String.valueOf(!ssl));p.setProperty("mail.smtp.ssl.checkserveridentity","true");p.setProperty("mail.smtp.connectiontimeout","5000");p.setProperty("mail.smtp.timeout","8000");p.setProperty("mail.smtp.writetimeout","8000");
 }
 public boolean enabled(){return sender.getHost()!=null&&!sender.getHost().isBlank()&&sender.getPassword()!=null&&!sender.getPassword().isBlank()&&!from.isBlank();}
 public void send(String to,String title,String body){if(!enabled())throw new ApiException(503,"邮件服务尚未开通，请稍后再试");try{var m=new SimpleMailMessage();m.setFrom(from);m.setTo(to);m.setSubject(title);m.setText(body);sender.send(m);}catch(org.springframework.mail.MailException e){throw new ApiException(503,"邮件暂时未能发送，请稍后重试");}}
}
