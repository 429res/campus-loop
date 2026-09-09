package edu.campusloop.web.auth.service;
import edu.campusloop.auth.AuthService;
import edu.campusloop.common.ApiException;
import edu.campusloop.web.auth.dto.AccountProfileRequest;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.user.mapper.UserMapper;
import edu.campusloop.web.upload.service.UploadReferenceService;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class AccountProfileService {
    private final UserMapper users;private final AuthService auth;private final UploadReferenceService uploads;
    public AccountProfileService(UserMapper users,AuthService auth,UploadReferenceService uploads){this.users=users;this.auth=auth;this.uploads=uploads;}
    @Transactional public AuthService.UserInfo save(User actor,AccountProfileRequest body){
        User user=users.selectByIdForUpdate(actor.getId());
        if(user==null||!"ACTIVE".equals(user.getStatus()))throw new ApiException(401,"账号不可用");
        if(!body.version().equals(user.getVersion())||body.version()==Integer.MAX_VALUE)throw new ApiException(409,"资料已更新，请刷新后重试");
        String avatar=clean(body.avatarUrl());
        String cover=clean(body.coverUrl());if(!cover.isEmpty())uploads.publicImage(actor.getId(),cover);
        if(!avatar.isEmpty())uploads.publicImage(actor.getId(),avatar);
        var update=new UpdateWrapper<User>().eq("id",actor.getId()).eq("version",body.version()).set("display_name",body.displayName().trim())
            .set("cover_url",cover).set("avatar_url",avatar).set("bio",clean(body.bio())).set("campus",clean(body.campus())).set("contact",clean(body.contact())).setSql("version=version+1");
        if(users.update(null,update)!=1)throw new ApiException(409,"资料已更新，请刷新后重试");
        return auth.info(users.selectById(actor.getId()));
    }
    private static String clean(String value){return value==null?"":value.trim();}
}
