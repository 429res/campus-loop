package edu.campusloop.web.admin.controller;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.ResultVo;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.admin.dto.UpdateUserAccessRequest;
import edu.campusloop.web.admin.service.AdminAccessService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/admin/users")
public class AdminAccessController {
 private final AdminAccessService access;
 public AdminAccessController(AdminAccessService access){this.access=access;}
 @PatchMapping("/{id}/access") public ResultVo<Void> update(@RequestAttribute(AuthInterceptor.USER) User actor,@PathVariable long id,@Valid @RequestBody UpdateUserAccessRequest body){access.update(actor.getId(),id,body);return ResultVo.success(null);}
}
