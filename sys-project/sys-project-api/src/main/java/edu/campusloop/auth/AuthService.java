package edu.campusloop.auth;
import edu.campusloop.common.ApiException;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.user.mapper.UserMapper;
import edu.campusloop.web.auth.entity.AuthSession;
import edu.campusloop.web.auth.mapper.AuthSessionMapper;
import edu.campusloop.web.auth.dto.LoginRequest;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
@Service
public class AuthService {
    public record UserInfo(long id, String username, String displayName, String role) {}
    public record LoginResult(String token, UserInfo user) {}
    private final UserMapper users; private final AuthSessionMapper sessions;
    private final PasswordService passwords; private final SecretKey key; private final int hours;
    private final String dummyHash;
    public AuthService(UserMapper users, AuthSessionMapper sessions, PasswordService passwords,
                       @Value("${campus.jwt-secret}") String secret, @Value("${campus.session-hours}") int hours) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new IllegalStateException("JWT_SECRET 必须至少 32 字节");
        this.users=users; this.sessions=sessions; this.passwords=passwords;
        this.key=Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); this.hours=hours;
        this.dummyHash=passwords.encode(UUID.randomUUID().toString());
    }
    @Transactional public LoginResult login(LoginRequest request) {
        User user=users.selectOne(new QueryWrapper<User>().eq("username", request.username().trim()));
        boolean valid=passwords.matches(request.password(), user == null || user.getPasswordHash() == null ? dummyHash : user.getPasswordHash());
        if (user == null || user.getPasswordHash() == null || !valid || !"ACTIVE".equals(user.getStatus())) throw new ApiException(401,"账号或密码不正确");
        Instant now=Instant.now(), expiry=now.plusSeconds(hours * 3600L);
        String id=UUID.randomUUID().toString();
        AuthSession session=new AuthSession(); session.setId(id); session.setUserId(user.getId());
        session.setExpiresAt(LocalDateTime.ofInstant(expiry, ZoneOffset.UTC)); sessions.insert(session);
        sessions.delete(new QueryWrapper<AuthSession>().lt("expires_at",LocalDateTime.now(ZoneOffset.UTC)));
        String token=Jwts.builder().issuer("campus-loop").subject(user.getId().toString()).id(id)
            .issuedAt(Date.from(now)).expiration(Date.from(expiry)).signWith(key).compact();
        return new LoginResult(token,info(user));
    }
    public User authenticate(String token) {
        Claims claims=claims(token); AuthSession session=sessions.selectById(claims.getId());
        if (session == null || !session.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))
            || !session.getUserId().toString().equals(claims.getSubject())) throw new ApiException(401,"登录已过期，请重新登录");
        User user=users.selectById(session.getUserId());
        if (user == null || !"ACTIVE".equals(user.getStatus()) || user.getPasswordHash()==null) throw new ApiException(401,"账号不可用");
        return user;
    }
    public void logout(String token) { sessions.deleteById(claims(token).getId()); }
    private Claims claims(String token) {
        try { return Jwts.parser().requireIssuer("campus-loop").verifyWith(key).build().parseSignedClaims(token).getPayload(); }
        catch (JwtException | IllegalArgumentException e) { throw new ApiException(401,"请重新登录"); }
    }
    public UserInfo info(User user) { return new UserInfo(user.getId(),user.getUsername(),user.getDisplayName(),user.getRole()); }
}
