package edu.campusloop.auth;
import edu.campusloop.common.ApiException;
import edu.campusloop.web.user.entity.User;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.*;
import java.util.Set;
@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String USER="campusUser";
    private final AuthService auth;
    private static final Set<String> PUBLIC=Set.of("GET /api/auth/options", "GET /api/auth/captcha", "POST /api/auth/email-code", "GET /api/spotlights", "POST /api/auth/login", "POST /api/auth/register", "GET /api/categories", "GET /api/items", "GET /api/items/{id}", "GET /api/matches", "GET /api/health", "GET /api/members/{id}", "GET /api/members/{id}/items");
    private static final Set<String> OPTIONAL=Set.of("GET /api/items/{id}/history","GET /api/items/{id}/history/{eventId}","GET /api/community/posts","GET /api/community/posts/{id}","GET /api/community/posts/{id}/replies");
    public AuthInterceptor(AuthService auth) { this.auth=auth; }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path=String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        if(path.startsWith("/api/auth/"))response.setHeader("Cache-Control","no-store");
        if ("OPTIONS".equals(request.getMethod()) || PUBLIC.contains(request.getMethod()+" "+path)) return true;
        if(OPTIONAL.contains(request.getMethod()+" "+path) && request.getHeader("Authorization")==null) return true;
        User user=auth.authenticate(token(request)); request.setAttribute(USER,user);
        if (path.startsWith("/api/admin/") && !"ADMIN".equals(user.getRole())) throw new ApiException(403,"需要管理员权限");
        if(!"ADMIN".equals(user.getRole())&&!Boolean.TRUE.equals(user.getLegacyExchangeAccess())&&!Boolean.TRUE.equals(user.getEmailVerified())&&!Set.of("GET","HEAD").contains(request.getMethod())&&!path.startsWith("/api/auth/")&&!path.startsWith("/api/account/")&&!path.startsWith("/api/notifications/"))throw new ApiException(403,"验证邮箱后才能发布内容或参与交换，请到我的账号绑定邮箱");
        response.setHeader("Cache-Control","private, no-store");response.addHeader("Vary","Authorization");
        if(path.startsWith("/api/admin/")) AdminPermissions.require(user,AdminPermissions.forPath(path));
        return true;
    }
    public static String token(HttpServletRequest request) {
        String header=request.getHeader("Authorization");
        if (header==null || !header.startsWith("Bearer ")) throw new ApiException(401,"请先登录");
        return header.substring(7);
    }
}
