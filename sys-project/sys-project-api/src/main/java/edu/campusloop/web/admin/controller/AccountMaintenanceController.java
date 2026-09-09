package edu.campusloop.web.admin.controller;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.*;
import edu.campusloop.web.admin.service.AccountMaintenanceService;
import edu.campusloop.web.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/admin/users")
public class AccountMaintenanceController {
 private final AccountMaintenanceService service;
 public AccountMaintenanceController(AccountMaintenanceService service){this.service=service;}
 @PostMapping public ResultVo<?> create(@RequestAttribute(AuthInterceptor.USER) User actor,@Valid @RequestBody AccountMaintenanceService.Create body){return ResultVo.success(service.create(actor.getId(),body));}
 @GetMapping("/{id}/profile") public ResultVo<?> get(@PathVariable long id){return ResultVo.success(service.get(id));}
 @PutMapping("/{id}/profile") public ResultVo<?> edit(@RequestAttribute(AuthInterceptor.USER) User actor,@PathVariable long id,@Valid @RequestBody AccountMaintenanceService.Edit body){return ResultVo.success(service.edit(actor.getId(),id,body));}
 @PostMapping("/{id}/password") public ResultVo<?> reset(@RequestAttribute(AuthInterceptor.USER) User actor,@PathVariable long id,@Valid @RequestBody AccountMaintenanceService.Reset body){service.reset(actor.getId(),id,body);return ResultVo.success(null);}
 @GetMapping("/{id}/activity") public ResultVo<?> activity(@PathVariable long id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ResultVo.success(service.activity(id,page,size));}
}
