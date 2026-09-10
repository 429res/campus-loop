package edu.campusloop.auth;
import edu.campusloop.common.ApiException;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.user.mapper.UserMapper;
import edu.campusloop.web.auth.entity.AuthSession;
import edu.campusloop.web.auth.mapper.AuthSessionMapper;
import edu.campusloop.web.auth.dto.LoginRequest;
import edu.campusloop.web.auth.dto.RegisterRequest;
import edu.campusloop.web.auth.dto.ChangePasswordRequest;
import edu.campusloop.web.auth.dto.UpdateProfileRequest;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
@Service
public class AuthService {
    public enum RegistrationMode { CLOSED, DEVELOPMENT_SELF_SERVICE, EMAIL_VERIFIED }
    public record UserInfo(long id, String username, String displayName, String role, String avatarUrl, String bio, String campus, String contact, Integer version, List<String> permissions,String coverUrl,String email,boolean emailVerified,boolean legacyExchangeAccess,boolean mailNotifications) {}
    public record LoginResult(String token, UserInfo user) {}
    @org.springframework.beans.factory.annotation.Autowired private edu.campusloop.web.auth.service.AccountSecurityAlerts securityAlerts;
    private final UserMapper users; private final AuthSessionMapper sessions;
    private final PasswordService passwords; private final SecretKey key; private final int hours; private final RegistrationMode registrationMode;
    private final String dummyHash;
    public AuthService(UserMapper users, AuthSessionMapper sessions, PasswordService passwords,
                       @Value("${campus.jwt-secret}") String secret, @Value("${campus.session-hours}") int hours,
                       @Value("${campus.registration-mode:CLOSED}") RegistrationMode registrationMode) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new IllegalStateException("JWT_SECRET 必须至少 32 字节");
        this.users=users; this.sessions=sessions; this.passwords=passwords;
        this.key=Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); this.hours=hours; this.registrationMode=registrationMode;
        this.dummyHash=passwords.encode(UUID.randomUUID().toString());
    }
    @org.springframework.beans.factory.annotation.Autowired private edu.campusloop.web.auth.service.EmailVerificationService emailVerification;
    @Transactional public UserInfo register(RegisterRequest request) {
        if(registrationMode==RegistrationMode.CLOSED)
            throw new ApiException(403,"当前未开放注册");
        User user=new User();
        if(registrationMode==RegistrationMode.EMAIL_VERIFIED){user.setEmail(emailVerification.consume(request.email(),"REGISTER",0,request.emailCode()));user.setEmailVerified(true);user.setLegacyExchangeAccess(false);}
        else user.setLegacyExchangeAccess(true);
        user.setUsername(request.username());user.setPasswordHash(passwords.encode(request.password()));
        user.setAdminPermissions("");user.setDisplayName(request.displayName());user.setRole("USER");user.setStatus("ACTIVE");user.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        try { users.insert(user); }
        catch(DuplicateKeyException e) { throw new ApiException(409,"用户名已存在"); }
        return info(user);
    }
    @Transactional public LoginResult login(LoginRequest request) {
        User user=users.selectOne(new QueryWrapper<User>().and(q->q.eq("username",request.username().trim()).or().eq("email",request.username().trim().toLowerCase(Locale.ROOT))).last("FOR UPDATE"));
        boolean valid=passwords.matches(request.password(), user == null || user.getPasswordHash() == null ? dummyHash : user.getPasswordHash());
        if (user == null || user.getPasswordHash() == null || !valid || !"ACTIVE".equals(user.getStatus()) || user.getDeletedAt()!=null) throw new ApiException(401,"账号或密码不正确");
        Instant now=Instant.now(), expiry=now.plusSeconds(hours * 3600L);
        String id=UUID.randomUUID().toString();
        AuthSession session=new AuthSession(); session.setId(id); session.setUserId(user.getId());
        session.setExpiresAt(LocalDateTime.ofInstant(expiry, ZoneOffset.UTC)); sessions.insert(session);
        sessions.delete(new QueryWrapper<AuthSession>().lt("expires_at",LocalDateTime.now(ZoneOffset.UTC)));
        String token=Jwts.builder().issuer("campus-loop").subject(user.getId().toString()).id(id)
            .issuedAt(Date.from(now)).expiration(Date.from(expiry)).signWith(key).compact();
        return new LoginResult(token,info(user));
    }
    @Transactional public UserInfo updateProfile(long userId, UpdateProfileRequest request) {
        int updated=users.update(null,new UpdateWrapper<User>().eq("id",userId).set("display_name",request.displayName().trim()).setSql("version=version+1"));
        if(updated!=1) throw new ApiException(401,"账号不可用");
        User user=users.selectById(userId);
        if(user==null || !"ACTIVE".equals(user.getStatus()) || user.getDeletedAt()!=null || user.getPasswordHash()==null) throw new ApiException(401,"账号不可用");
        return info(user);
    }
    @Transactional public void changePassword(long userId, ChangePasswordRequest request) {
        User user=users.selectOne(new QueryWrapper<User>().eq("id",userId).last("FOR UPDATE"));
        if(user==null || !"ACTIVE".equals(user.getStatus()) || user.getDeletedAt()!=null || user.getPasswordHash()==null) throw new ApiException(401,"账号不可用");
        if(!passwords.matches(request.currentPassword(),user.getPasswordHash())) throw new ApiException(400,"旧密码不正确");
        String passwordHash=passwords.encode(request.newPassword());
        int updated=users.update(null,new UpdateWrapper<User>().eq("id",userId).set("password_hash",passwordHash));
        if(updated!=1) throw new ApiException(409,"账号状态冲突，请重试");
        sessions.delete(new QueryWrapper<AuthSession>().eq("user_id",userId));
        securityAlerts.passwordChanged(userId,false);
    }
    public User authenticate(String token) {
        Claims claims=claims(token); AuthSession session=sessions.selectById(claims.getId());
        if (session == null || !session.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))
            || !session.getUserId().toString().equals(claims.getSubject())) throw new ApiException(401,"登录已过期，请重新登录");
        User user=users.selectById(session.getUserId());
        if (user == null || !"ACTIVE".equals(user.getStatus()) || user.getDeletedAt()!=null || user.getPasswordHash()==null) throw new ApiException(401,"账号不可用");
        return user;
    }
    public void logout(String token) { sessions.deleteById(claims(token).getId()); }
    private Claims claims(String token) {
        try { return Jwts.parser().requireIssuer("campus-loop").verifyWith(key).build().parseSignedClaims(token).getPayload(); }
        catch (JwtException | IllegalArgumentException e) { throw new ApiException(401,"请重新登录"); }
    }
    public UserInfo info(User user) { return new UserInfo(user.getId(),user.getUsername(),user.getDisplayName(),user.getRole(),user.getAvatarUrl(),user.getBio(),user.getCampus(),user.getContact(),user.getVersion(),AdminPermissions.of(user),user.getCoverUrl(),user.getEmail(),Boolean.TRUE.equals(user.getEmailVerified()),Boolean.TRUE.equals(user.getLegacyExchangeAccess()),Boolean.TRUE.equals(user.getMailNotifications())); }
}
