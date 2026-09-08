package edu.campusloop.web.admin.service;

import edu.campusloop.common.*;
import edu.campusloop.web.admin.dto.UpdateUserStatusRequest;
import edu.campusloop.web.admin.entity.UserStatusAudit;
import edu.campusloop.web.admin.mapper.UserStatusAuditMapper;
import edu.campusloop.web.admin.vo.*;
import edu.campusloop.web.auth.entity.AuthSession;
import edu.campusloop.web.auth.mapper.AuthSessionMapper;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AdminUserService {
    private static final Set<String> ROLES=Set.of("ADMIN","USER");
    private static final Set<String> STATUSES=Set.of("ACTIVE","DISABLED");
    private final UserMapper users;
    private final AuthSessionMapper sessions;
    private final UserStatusAuditMapper audits;

    public AdminUserService(UserMapper users,AuthSessionMapper sessions,UserStatusAuditMapper audits) {
        this.users=users;this.sessions=sessions;this.audits=audits;
    }

    public PageResult<AdminUserView> page(int page,int size,String keyword,String role,String status) {
        validatePage(page,size);
        String search=normalizeKeyword(keyword);
        role=normalizeFilter(role,ROLES,"角色筛选不正确");
        status=normalizeFilter(status,STATUSES,"状态筛选不正确");
        QueryWrapper<User> query=new QueryWrapper<>();
        if(search!=null) query.and(q->q.like("username",search).or().like("display_name",search));
        if(role!=null) query.eq("role",role);
        if(status!=null) query.eq("status",status);
        query.orderByDesc("created_at","id");
        Page<User> result=users.selectPage(new Page<>(page,size),query);
        return new PageResult<>(result.getRecords().stream().map(this::view).toList(),result.getTotal(),page,size);
    }

    @Transactional
    public AdminUserView updateStatus(long operatorId,long targetId,UpdateUserStatusRequest request) {
        List<User> admins=users.selectAdminsForUpdate();
        User operator=admins.stream().filter(user->Objects.equals(user.getId(),operatorId)).findFirst().orElse(null);
        if(operator==null || !"ADMIN".equals(operator.getRole()) || !"ACTIVE".equals(operator.getStatus()) || operator.getPasswordHash()==null)
            throw new ApiException(401,"账号不可用");
        if(operatorId==targetId && "DISABLED".equals(request.status())) throw new ApiException(409,"不能停用当前管理员自己的账号");

        User target=admins.stream().filter(user->Objects.equals(user.getId(),targetId)).findFirst().orElseGet(()->users.selectByIdForUpdate(targetId));
        if(target==null) throw new ApiException(404,"用户不存在");
        if(!Objects.equals(target.getVersion(),request.version())) throw new ApiException(409,"账号状态已变化，请刷新后重试");
        if(Objects.equals(target.getStatus(),request.status())) return view(target);
        if("ADMIN".equals(target.getRole()) && "DISABLED".equals(request.status())
            && admins.stream().filter(user->"ACTIVE".equals(user.getStatus()) && user.getPasswordHash()!=null).count()<=1)
            throw new ApiException(409,"不能停用最后一个可用管理员");

        String previousStatus=target.getStatus();int previousVersion=target.getVersion();
        int updated=users.updateStatusIfVersion(targetId,previousStatus,request.status(),previousVersion);
        if(updated!=1) throw new ApiException(409,"账号状态已变化，请刷新后重试");
        if("DISABLED".equals(request.status())) sessions.delete(new QueryWrapper<AuthSession>().eq("user_id",targetId));

        UserStatusAudit audit=new UserStatusAudit();audit.setTargetUserId(targetId);audit.setOperatorUserId(operatorId);
        audit.setPreviousStatus(previousStatus);audit.setNewStatus(request.status());audit.setReason(request.reason().trim());
        audit.setPreviousVersion(previousVersion);audit.setNewVersion(previousVersion+1);audit.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        audits.insert(audit);
        target.setStatus(request.status());target.setVersion(previousVersion+1);
        return view(target);
    }

    public PageResult<UserStatusAuditView> audits(long targetId,int page,int size) {
        validatePage(page,size);
        if(users.selectById(targetId)==null) throw new ApiException(404,"用户不存在");
        QueryWrapper<UserStatusAudit> query=new QueryWrapper<UserStatusAudit>().eq("target_user_id",targetId).orderByDesc("created_at","id");
        Page<UserStatusAudit> result=audits.selectPage(new Page<>(page,size),query);
        Set<Long> operatorIds=result.getRecords().stream().map(UserStatusAudit::getOperatorUserId).collect(Collectors.toSet());
        Map<Long,User> operators=operatorIds.isEmpty()?Map.of():users.selectBatchIds(operatorIds).stream().collect(Collectors.toMap(User::getId,Function.identity()));
        List<UserStatusAuditView> views=result.getRecords().stream().map(a->{
            User operator=operators.get(a.getOperatorUserId());
            return new UserStatusAuditView(a.getId(),a.getTargetUserId(),a.getOperatorUserId(),operator==null?null:operator.getUsername(),
                operator==null?null:operator.getDisplayName(),a.getPreviousStatus(),a.getNewStatus(),a.getReason(),
                a.getPreviousVersion(),a.getNewVersion(),a.getCreatedAt());
        }).toList();
        return new PageResult<>(views,result.getTotal(),page,size);
    }

    private AdminUserView view(User user) {
        return new AdminUserView(user.getId(),user.getUsername(),user.getDisplayName(),user.getRole(),user.getStatus(),user.getVersion(),user.getCreatedAt());
    }
    private void validatePage(int page,int size) {
        if(page<1 || size<1 || size>100) throw new ApiException(400,"分页参数不正确");
    }
    private String normalizeKeyword(String keyword) {
        if(keyword==null || keyword.isBlank()) return null;
        String normalized=keyword.trim();
        if(normalized.length()>100) throw new ApiException(400,"搜索参数不正确");
        return normalized;
    }
    private String normalizeFilter(String value,Set<String> allowed,String message) {
        if(value==null || value.isBlank()) return null;
        String normalized=value.trim();
        if(!allowed.contains(normalized)) throw new ApiException(400,message);
        return normalized;
    }
}
