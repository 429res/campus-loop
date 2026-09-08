package edu.campusloop.web.admin.controller;
import edu.campusloop.common.*;
import edu.campusloop.web.item.service.ItemService;
import edu.campusloop.web.item.vo.ItemView;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.web.admin.dto.UpdateUserStatusRequest;
import edu.campusloop.web.admin.service.AdminUserService;
import edu.campusloop.web.admin.service.AdminStatsService;
import edu.campusloop.web.admin.vo.*;
import edu.campusloop.web.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final ItemService items;private final AdminStatsService stats;private final AdminUserService adminUsers;
    public AdminController(ItemService items,AdminStatsService stats,AdminUserService adminUsers){this.items=items;this.stats=stats;this.adminUsers=adminUsers;}
    @GetMapping("/items") public ResultVo<PageResult<ItemView>> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int size,
        @RequestParam(required=false) String keyword,@RequestParam(required=false) Long categoryId){return ResultVo.success(items.page(page,size,keyword,categoryId,true));}
    @GetMapping("/stats") public ResultVo<AdminStatsView> stats(){return ResultVo.success(stats.stats());}
    @GetMapping("/users") public ResultVo<PageResult<AdminUserView>> users(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int size,
        @RequestParam(required=false) String keyword,@RequestParam(required=false) String role,@RequestParam(required=false) String status) {
        return ResultVo.success(adminUsers.page(page,size,keyword,role,status));
    }
    @PatchMapping("/users/{id}/status") public ResultVo<AdminUserView> updateUserStatus(@PathVariable long id,
        @RequestAttribute(AuthInterceptor.USER) User operator,@Valid @RequestBody UpdateUserStatusRequest body) {
        return ResultVo.success(adminUsers.updateStatus(operator.getId(),id,body));
    }
    @GetMapping("/users/{id}/status-audits") public ResultVo<PageResult<UserStatusAuditView>> userStatusAudits(@PathVariable long id,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ResultVo.success(adminUsers.audits(id,page,size));
    }
}
