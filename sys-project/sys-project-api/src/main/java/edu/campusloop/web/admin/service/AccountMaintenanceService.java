package edu.campusloop.web.admin.service;

import edu.campusloop.auth.*;
import edu.campusloop.common.*;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.user.mapper.UserMapper;
import edu.campusloop.web.auth.mapper.AuthSessionMapper;
import edu.campusloop.web.auth.entity.AuthSession;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import jakarta.validation.constraints.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class AccountMaintenanceService {
 public record Create(@NotBlank @Pattern(regexp="[A-Za-z0-9_.-]{3,64}") String username,@NotBlank @Size(min=12,max=64) String password,
  @NotBlank @Size(max=64) String displayName,@NotNull @Pattern(regexp="USER|ADMIN") String role,
  @NotNull @Size(max=20) List<@NotBlank String> permissions,@NotBlank @Size(max=500) String reason){}
 public record Edit(@NotNull @Min(0) Integer version,@NotBlank @Size(max=64) String displayName,@Size(max=100) String campus,@Size(max=300) String bio,@NotBlank @Size(max=500) String reason){}
 public record Reset(@NotNull @Min(0) Integer version,@NotBlank @Size(min=12,max=64) String password,@NotBlank @Size(max=500) String reason){}
 public record Profile(long id,String username,String displayName,String role,String status,int version,String campus,String bio,String avatarUrl,List<String> permissions){}
 public record Activity(String key,String action,String reason,String actorName,LocalDateTime createdAt){}
 @org.springframework.beans.factory.annotation.Autowired private edu.campusloop.web.auth.service.AccountSecurityAlerts securityAlerts;
 private final UserMapper users;private final PasswordService passwords;private final AuthSessionMapper sessions;private final JdbcTemplate db;
 public AccountMaintenanceService(UserMapper users,PasswordService passwords,AuthSessionMapper sessions,JdbcTemplate db){this.users=users;this.passwords=passwords;this.sessions=sessions;this.db=db;}
 @Transactional public Profile create(long actor,Create body){
  var operator=operator(actor);AdminPermissions.require(operator,"USERS");
  if("ADMIN".equals(body.role()))AdminPermissions.require(operator,"ALL");
  var scopes=new TreeSet<>(body.permissions());
  if(!AdminPermissions.SCOPES.containsAll(scopes)||("USER".equals(body.role())&&!scopes.isEmpty())||("ADMIN".equals(body.role())&&scopes.isEmpty()))throw new ApiException(400,"请选择正确的管理权限");
  if(scopes.contains("ALL")){scopes.clear();scopes.add("ALL");}
  var user=new User();user.setUsername(body.username());user.setDisplayName(body.displayName().trim());user.setRole(body.role());user.setAdminPermissions(String.join(",",scopes));
  user.setPasswordHash(passwords.encode(body.password()));user.setStatus("ACTIVE");user.setVersion(0);user.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
  try{users.insert(user);}catch(DuplicateKeyException e){throw new ApiException(409,"用户名已存在，请更换或在账号列表中查找");}
  audit(actor,user.getId(),"CREATED",body.reason());return profile(user);
 }
 public Profile get(long id){return profile(require(users.selectById(id)));}
 @Transactional public Profile edit(long actor,long id,Edit body){
  var operator=operator(actor);var target=target(operator,id,body.version());
  users.update(null,new UpdateWrapper<User>().eq("id",id).eq("version",body.version()).set("display_name",body.displayName().trim()).set("campus",trim(body.campus())).set("bio",trim(body.bio())).setSql("version=version+1"));
  audit(actor,id,"PROFILE_UPDATED",body.reason());return get(id);
 }
 @Transactional public void reset(long actor,long id,Reset body){
  var operator=operator(actor);AdminPermissions.require(operator,"ALL");
  if(actor==id)throw new ApiException(409,"请在个人账号中修改自己的密码");
  target(operator,id,body.version());
  users.update(null,new UpdateWrapper<User>().eq("id",id).eq("version",body.version()).set("password_hash",passwords.encode(body.password())).setSql("version=version+1"));
  sessions.delete(new QueryWrapper<AuthSession>().eq("user_id",id));audit(actor,id,"PASSWORD_RESET",body.reason());securityAlerts.passwordChanged(id,true);
 }
 public PageResult<Activity> activity(long id,int page,int size){
  require(users.selectById(id));if(page<1||size<1||size>100)throw new ApiException(400,"分页参数不正确");
  String union="SELECT CONCAT('account-',id) AS activity_key,action,reason,actor_user_id AS actor_id,created_at FROM cl_account_audit WHERE target_user_id=? UNION ALL SELECT CONCAT('status-',id),CONCAT('STATUS_',new_status),reason,operator_user_id,created_at FROM cl_user_status_audit WHERE target_user_id=? UNION ALL SELECT CONCAT('access-',id),'ACCESS_CHANGED',reason,actor_user_id,created_at FROM cl_user_access_audit WHERE target_user_id=?";
  long total=db.queryForObject("SELECT COUNT(*) FROM ("+union+") a",Long.class,id,id,id);
  var rows=db.query("SELECT a.*,u.display_name FROM ("+union+") a LEFT JOIN cl_user u ON u.id=a.actor_id ORDER BY a.created_at DESC,a.activity_key DESC LIMIT ? OFFSET ?",(rs,n)->new Activity(rs.getString("activity_key"),rs.getString("action"),rs.getString("reason"),rs.getString("display_name"),rs.getObject("created_at",LocalDateTime.class)),id,id,id,size,(long)(page-1)*size);
  return new PageResult<>(rows,total,page,size);
 }
 private User operator(long id){var admins=users.selectAdminsForUpdate();var actor=admins.stream().filter(u->u.getId()==id).findFirst().orElseThrow(()->new ApiException(403,"需要管理员权限"));AdminPermissions.require(actor,"USERS");return actor;}
 private User target(User actor,long id,int version){var target=require(users.selectByIdForUpdate(id));if("ADMIN".equals(target.getRole()))AdminPermissions.require(actor,"ALL");if(target.getVersion()!=version||version==Integer.MAX_VALUE)throw new ApiException(409,"账号已更新，请刷新后重试");return target;}
 private User require(User user){if(user==null||user.getDeletedAt()!=null||Boolean.TRUE.equals(user.getSystemAccount()))throw new ApiException(404,"账号不存在");return user;}
 private Profile profile(User u){return new Profile(u.getId(),u.getUsername(),u.getDisplayName(),u.getRole(),u.getStatus(),u.getVersion(),u.getCampus(),u.getBio(),u.getAvatarUrl(),AdminPermissions.of(u));}
 private String trim(String value){return value==null?"":value.trim();}
 private void audit(long actor,long id,String action,String reason){db.update("INSERT INTO cl_account_audit(target_user_id,actor_user_id,action,reason,created_at) VALUES(?,?,?,?,?)",id,actor,action,reason.trim(),LocalDateTime.now(ZoneOffset.UTC));}
}
