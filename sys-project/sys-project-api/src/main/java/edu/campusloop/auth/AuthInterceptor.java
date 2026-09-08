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
    private static final Set<String> PUBLIC=Set.of("POST /api/auth/login", "POST /api/auth/register", "GET /api/categories", "GET /api/items", "GET /api/items/{id}", "GET /api/matches", "GET /api/health");
    private static final Set<String> OPTIONAL=Set.of("GET /api/items/{id}/history","GET /api/items/{id}/history/{eventId}");
    public AuthInterceptor(AuthService auth) { this.auth=auth; }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path=String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        if ("OPTIONS".equals(request.getMethod()) || PUBLIC.contains(request.getMethod()+" "+path)) return true;
        if(OPTIONAL.contains(request.getMethod()+" "+path) && request.getHeader("Authorization")==null) return true;
        User user=auth.authenticate(token(request)); request.setAttribute(USER,user);
        if (path.startsWith("/api/admin/") && !"ADMIN".equals(user.getRole())) throw new ApiException(403,"需要管理员权限");
        return true;
    }
    public static String token(HttpServletRequest request) {
        String header=request.getHeader("Authorization");
        if (header==null || !header.startsWith("Bearer ")) throw new ApiException(401,"请先登录");
        return header.substring(7);
    }
}
