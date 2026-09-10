package edu.campusloop.web.auth.service;

import edu.campusloop.web.user.mapper.UserMapper;
import edu.campusloop.web.notification.service.NotificationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class AccountSecurityAlerts {
 private final UserMapper users; private final JdbcTemplate db; private final NotificationService notifications;
 public AccountSecurityAlerts(UserMapper users,JdbcTemplate db,NotificationService notifications){this.users=users;this.db=db;this.notifications=notifications;}
 @Transactional public void login(long id,String address){
  var u=users.selectByIdForUpdate(id);
  if(u==null||u.getDeletedAt()!=null||!"ACTIVE".equals(u.getStatus())||address==null||address.length()>64)return;
  String previous=u.getLastLoginAddress();
  db.update("UPDATE cl_user SET last_login_address=? WHERE id=?",address,id);
  if(previous!=null&&!previous.equals(address)&&Boolean.TRUE.equals(u.getEmailVerified()))
   notifications.send(id,"ACCOUNT_SECURITY","登录地址发生变化","你的账号刚从新的网络地址 "+address+" 成功登录。网络切换或 VPN 也可能造成变化；如果不是本人操作，请立即修改密码。","/pages/profile-settings/profile-settings","security-login-"+UUID.randomUUID());
 }
 @Transactional public void passwordChanged(long id,boolean reset){
  var u=users.selectById(id);if(u==null||!Boolean.TRUE.equals(u.getEmailVerified()))return;
  notifications.send(id,"ACCOUNT_SECURITY",reset?"账号密码已重置":"账号密码已修改",reset?"管理员已重置你的账号密码，原有登录会话已退出。如果不是你申请的操作，请联系平台管理员。":"你的账号密码已修改，原有登录会话已退出。如果不是本人操作，请联系平台管理员。","/pages/profile-settings/profile-settings","security-password-"+UUID.randomUUID());
 }
}
