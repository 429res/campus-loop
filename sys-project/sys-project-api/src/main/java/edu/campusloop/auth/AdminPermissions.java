package edu.campusloop.auth;
import edu.campusloop.common.ApiException;
import edu.campusloop.web.user.entity.User;
import java.util.*;
public final class AdminPermissions {
    private AdminPermissions() {}
    public static final Set<String> SCOPES=Set.of("ALL","USERS","ITEMS","CATEGORIES","EXCHANGES","HISTORY","REPORTS","OPERATIONS","COMMUNITY");
    public static List<String> of(User user) {
        if(!"ADMIN".equals(user.getRole())) return List.of();
        String raw=user.getAdminPermissions();
        return raw==null?List.of("ALL"):Arrays.stream(raw.split(",")).filter(SCOPES::contains).distinct().sorted().toList();
    }
    public static boolean has(User user,String permission) {var scopes=of(user);return scopes.contains("ALL")||scopes.contains(permission);}
    public static void require(User user,String permission) {
        if(!"ACTIVE".equals(user.getStatus())||!has(user,permission)) throw new ApiException(403,"没有此操作权限");
    }
    public static String forPath(String path) {
        if(path.startsWith("/api/admin/users")) return path.endsWith("/access")?"ALL":"USERS";
        if(path.startsWith("/api/admin/community")) return "COMMUNITY";
        if(path.startsWith("/api/admin/items")) return "ITEMS";
        if(path.startsWith("/api/admin/categories")) return "CATEGORIES";
        if(path.startsWith("/api/admin/matches")) return "EXCHANGES";
        if(path.startsWith("/api/admin/exchange")) return "EXCHANGES";
        if(path.startsWith("/api/admin/history")) return "HISTORY";
        if(path.startsWith("/api/admin/reports")) return "REPORTS";
        if(path.startsWith("/api/admin/stats")||path.startsWith("/api/admin/operations")) return "OPERATIONS";
        return "ALL";
    }
}
