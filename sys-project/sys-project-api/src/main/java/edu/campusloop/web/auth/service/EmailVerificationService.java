package edu.campusloop.web.auth.service;
import edu.campusloop.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import java.time.*;
import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
@Service public class EmailVerificationService {
 @org.springframework.beans.factory.annotation.Value("${campus.jwt-secret}") private String codeSecret;
 private final JdbcTemplate db;private final MailDelivery mail;private final TransactionTemplate tx;private final SecureRandom random=new SecureRandom();
 public EmailVerificationService(JdbcTemplate db,MailDelivery mail,PlatformTransactionManager tm){this.db=db;this.mail=mail;tx=new TransactionTemplate(tm);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
 public static String normalize(String email){if(email==null||email.length()>254||!email.trim().matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,}"))throw new ApiException(400,"请输入有效邮箱");return email.trim().toLowerCase(Locale.ROOT);}
 public boolean enabled(){return mail.enabled();}
 public void send(String address,String purpose,long user){String email=normalize(address);if(!mail.enabled())throw new ApiException(503,"邮件服务尚未开通，请稍后再试");
  String code=String.format("%06d",random.nextInt(1000000));var now=LocalDateTime.now(ZoneOffset.UTC);
  Boolean accepted=tx.execute(s->{
   // The primary key serializes resends and the transaction survives failed request rollbacks.
   try{db.update("INSERT INTO cl_email_code(email,purpose,user_id,code_hash,expires_at,sent_at) VALUES(?,?,?,?,?,?)",email,purpose,user,"",now.minusSeconds(1),now.minusDays(1));}catch(org.springframework.dao.DuplicateKeyException ignored){}
   var row=db.queryForMap("SELECT * FROM cl_email_code WHERE email=? AND purpose=? AND user_id=? FOR UPDATE",email,purpose,user);
   if(((java.sql.Timestamp)row.get("sent_at")).toLocalDateTime().isAfter(now.minusSeconds(60)))return false;
   db.update("UPDATE cl_email_code SET code_hash=?,expires_at=?,sent_at=?,attempts=0,consumed=FALSE WHERE email=? AND purpose=? AND user_id=?",hash(email+purpose+user+code),now.plusMinutes(10),now,email,purpose,user);return true;
  });if(!Boolean.TRUE.equals(accepted))throw new ApiException(429,"请等待 60 秒后再获取验证码");
  mail.send(email,"Campus Loop 邮箱验证码","你的验证码是："+code+"\n10 分钟内有效。请勿向他人透露；如果不是你本人操作，请忽略本邮件。");
 }
 public String consume(String address,String purpose,long user,String code){String email=normalize(address);if(code==null||!code.matches("[0-9]{6}"))throw new ApiException(400,"请输入 6 位邮箱验证码");
  Boolean valid=tx.execute(s->{var rows=db.queryForList("SELECT * FROM cl_email_code WHERE email=? AND purpose=? AND user_id=? FOR UPDATE",email,purpose,user);if(rows.isEmpty())return false;var row=rows.get(0);if(Boolean.TRUE.equals(row.get("consumed"))||((Number)row.get("attempts")).intValue()>=5||!((java.sql.Timestamp)row.get("expires_at")).toLocalDateTime().isAfter(LocalDateTime.now(ZoneOffset.UTC)))return false;
   boolean ok=MessageDigest.isEqual(hash(email+purpose+user+code).getBytes(StandardCharsets.UTF_8),row.get("code_hash").toString().getBytes(StandardCharsets.UTF_8));
   db.update("UPDATE cl_email_code SET attempts=attempts+1,consumed=? WHERE email=? AND purpose=? AND user_id=?",ok,email,purpose,user);return ok;});
  if(!Boolean.TRUE.equals(valid))throw new ApiException(400,"验证码错误、已使用或已过期，请重新获取");return email;
 }
 private String hmac(String value)throws Exception{var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(codeSecret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}
 private String hash(String value){try{return hmac(value);}catch(Exception e){throw new IllegalStateException(e);}}
}
