package edu.campusloop.web.admin.controller;
import edu.campusloop.common.*;
import edu.campusloop.web.item.service.ItemService;
import edu.campusloop.web.item.vo.ItemView;
import edu.campusloop.web.item.entity.Item;
import edu.campusloop.web.item.mapper.ItemMapper;
import edu.campusloop.web.user.mapper.UserMapper;
import edu.campusloop.web.matching.service.MatchingService;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.web.admin.dto.UpdateUserStatusRequest;
import edu.campusloop.web.admin.service.AdminUserService;
import edu.campusloop.web.admin.vo.*;
import edu.campusloop.web.user.entity.User;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final ItemService items;private final ItemMapper itemMapper;private final UserMapper users;private final MatchingService matching;private final AdminUserService adminUsers;
    public AdminController(ItemService items,ItemMapper itemMapper,UserMapper users,MatchingService matching,AdminUserService adminUsers){this.items=items;this.itemMapper=itemMapper;this.users=users;this.matching=matching;this.adminUsers=adminUsers;}
    @GetMapping("/items") public ResultVo<PageResult<ItemView>> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int size,
        @RequestParam(required=false) String keyword,@RequestParam(required=false) Long categoryId){return ResultVo.success(items.page(page,size,keyword,categoryId,true));}
    @GetMapping("/stats") public ResultVo<Map<String,Long>> stats(){return ResultVo.success(Map.of("users",users.selectCount(null),"items",itemMapper.selectCount(null),
        "availableItems",itemMapper.selectCount(new QueryWrapper<Item>().eq("status","AVAILABLE")),"recommendations",(long)matching.recommendations().size()));}
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
