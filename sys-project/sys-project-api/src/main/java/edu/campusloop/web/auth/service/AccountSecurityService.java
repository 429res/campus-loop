package edu.campusloop.web.auth.service;
import edu.campusloop.auth.*;
import edu.campusloop.common.ApiException;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.user.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service public class AccountSecurityService {
 private final UserMapper users;private final JdbcTemplate db;private final PasswordService passwords;private final EmailVerificationService emails;private final AuthService auth;
 public AccountSecurityService(UserMapper users,JdbcTemplate db,PasswordService passwords,EmailVerificationService emails,AuthService auth){this.users=users;this.db=db;this.passwords=passwords;this.emails=emails;this.auth=auth;}
 @Transactional public AuthService.UserInfo bind(long actor,String email,String code,String password){var u=users.selectByIdForUpdate(actor);requirePassword(u,password);String verified=emails.consume(email,"BIND",actor,code);try{db.update("UPDATE cl_user SET email=?,email_verified=TRUE,version=version+1 WHERE id=?",verified,actor);}catch(org.springframework.dao.DuplicateKeyException e){throw new ApiException(409,"该邮箱已绑定其他账号");}return auth.info(users.selectById(actor));}
 @Transactional public void mailPreference(long actor,boolean enabled){var u=users.selectByIdForUpdate(actor);if(enabled&&!Boolean.TRUE.equals(u.getEmailVerified()))throw new ApiException(400,"请先绑定并验证邮箱");db.update("UPDATE cl_user SET mail_notifications=?,version=version+1 WHERE id=?",enabled,actor);}
 @Transactional public void delete(long actor,long target,int version,String password,String reason){
  // Same admin/user lock order as account maintenance and exchange creation.
  User operator;if(actor!=target){var admins=users.selectAdminsForUpdate();operator=admins.stream().filter(x->x.getId()==actor).findFirst().orElseThrow(()->new ApiException(403,"需要管理员权限"));AdminPermissions.require(operator,"USERS");}else operator=null;
  var u=users.selectByIdForUpdate(target);if(u==null||u.getDeletedAt()!=null||Boolean.TRUE.equals(u.getSystemAccount()))throw new ApiException(404,"账号不存在");if(u.getVersion()!=version)throw new ApiException(409,"账号已更新，请刷新后重试");
  if(actor==target)requirePassword(u,password);if("ADMIN".equals(u.getRole()))throw new ApiException(409,"管理员账号不能直接注销，请先调整账号职责");
  if(db.queryForObject("SELECT COUNT(*) FROM cl_exchange_participant p JOIN cl_exchange e ON e.id=p.exchange_id WHERE p.user_id=? AND e.status NOT IN ('COMPLETED','CANCELLED','EXPIRED')",Long.class,target)>0)throw new ApiException(409,"还有未结束的交换，请完成或取消后再注销");
  if(db.queryForObject("SELECT COUNT(*) FROM cl_item_hold h JOIN cl_item i ON i.id=h.item_id WHERE i.owner_id=?",Long.class,target)>0)throw new ApiException(409,"物品仍被交换占用，请先处理交换");
  db.update("DELETE FROM cl_auth_session WHERE user_id=?",target);
  db.update("UPDATE cl_item SET status='HIDDEN',version=version+1 WHERE owner_id=? AND status<>'EXCHANGED'",target);
  db.update("UPDATE cl_demand SET status='INACTIVE',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE owner_id=? AND status='ACTIVE'",target);
  db.update("UPDATE cl_community_post SET status='DELETED',version=version+1 WHERE author_id=? AND status<>'DELETED'",target);
  db.update("UPDATE cl_community_reply SET status='DELETED' WHERE author_id=?",target);
  db.update("DELETE FROM cl_community_like WHERE user_id=?",target);
  db.update("DELETE FROM cl_email_code WHERE user_id=?",target);
  db.update("UPDATE cl_user SET username=?,password_hash=NULL,display_name='已注销用户',email=NULL,email_verified=FALSE,mail_notifications=FALSE,avatar_url=NULL,cover_url=NULL,last_login_address=NULL,bio=NULL,campus=NULL,contact=NULL,status='DISABLED',deleted_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=?","deleted_"+target+"_"+UUID.randomUUID().toString().substring(0,8),target);
  db.update("INSERT INTO cl_account_audit(target_user_id,actor_user_id,action,reason,created_at) VALUES(?,?,'DELETED',?,CURRENT_TIMESTAMP)",target,actor,reason);
 }
 private void requirePassword(User u,String password){if(u==null||u.getDeletedAt()!=null||Boolean.TRUE.equals(u.getSystemAccount())||!"ACTIVE".equals(u.getStatus())||password==null||!passwords.matches(password,u.getPasswordHash()))throw new ApiException(400,"当前密码不正确");}
}
