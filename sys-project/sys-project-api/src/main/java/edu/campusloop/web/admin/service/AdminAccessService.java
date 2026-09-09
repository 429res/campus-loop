package edu.campusloop.web.admin.service;
import edu.campusloop.auth.AdminPermissions;
import edu.campusloop.common.*;
import edu.campusloop.web.admin.dto.UpdateUserAccessRequest;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.user.mapper.UserMapper;
import edu.campusloop.web.auth.entity.AuthSession;
import edu.campusloop.web.auth.mapper.AuthSessionMapper;
import edu.campusloop.web.notification.service.NotificationService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
@Service
public class AdminAccessService {
 private final UserMapper users;private final AuthSessionMapper sessions;private final JdbcTemplate db;private final NotificationService notifications;
 public AdminAccessService(UserMapper users,AuthSessionMapper sessions,JdbcTemplate db,NotificationService notifications){this.users=users;this.sessions=sessions;this.db=db;this.notifications=notifications;}
 @Transactional public void update(long actor,long target,UpdateUserAccessRequest request){
  var admins=users.selectAdminsForUpdate();var operator=admins.stream().filter(u->u.getId()==actor).findFirst().orElseThrow(()->new ApiException(403,"需要最高管理权限"));
  AdminPermissions.require(operator,"ALL");
  var user=admins.stream().filter(u->u.getId()==target).findFirst().orElseGet(()->users.selectByIdForUpdate(target));
  if(user==null)throw new ApiException(404,"账号不存在");
  if(!user.getVersion().equals(request.version())||request.version()==Integer.MAX_VALUE)throw new ApiException(409,"账号已更新，请刷新后重试");
  var permissions=new TreeSet<>(request.permissions());
  if(!AdminPermissions.SCOPES.containsAll(permissions)||("USER".equals(request.role())&&!permissions.isEmpty())||("ADMIN".equals(request.role())&&permissions.isEmpty()))throw new ApiException(400,"权限配置不正确");
  if(permissions.contains("ALL")){permissions.clear();permissions.add("ALL");}
  if(actor==target)throw new ApiException(409,"不能修改当前账号自己的权限");
  if(AdminPermissions.has(user,"ALL")&&(!"ADMIN".equals(request.role())||!permissions.contains("ALL"))&&admins.stream().filter(u->"ACTIVE".equals(u.getStatus())&&AdminPermissions.has(u,"ALL")).count()<=1)throw new ApiException(409,"需要保留至少一个可用的最高权限管理员");
  String next=String.join(",",permissions),previous=Objects.toString(user.getAdminPermissions(),"ALL");
  if(user.getRole().equals(request.role())&&previous.equals(next))return;
  if(users.update(null,new UpdateWrapper<User>().eq("id",target).eq("version",request.version()).set("role",request.role()).set("admin_permissions",next).setSql("version=version+1"))!=1)throw new ApiException(409,"账号已更新");
  db.update("INSERT INTO cl_user_access_audit(target_user_id,actor_user_id,previous_role,new_role,previous_permissions,new_permissions,previous_version,new_version,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",target,actor,user.getRole(),request.role(),previous,next,request.version(),request.version()+1,request.reason().trim(),LocalDateTime.now(ZoneOffset.UTC));
  sessions.delete(new QueryWrapper<AuthSession>().eq("user_id",target));
  notifications.send(target,"ACCOUNT","账号权限已更新","请重新登录查看当前权限。","/pages/profile/profile","ACCESS:"+target+":"+(request.version()+1));
 }
}
