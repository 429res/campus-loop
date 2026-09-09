package edu.campusloop.web.auth.controller;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.ResultVo;
import edu.campusloop.web.auth.service.*;
import edu.campusloop.web.user.entity.User;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
@RestController public class AccountSecurityController {
 private final AccountSecurityService service;private final EmailVerificationService emails;
 public AccountSecurityController(AccountSecurityService service,EmailVerificationService emails){this.service=service;this.emails=emails;}
 public record Email(@NotBlank @jakarta.validation.constraints.Email @Size(max=254)String email){}
 public record Bind(@NotBlank @jakarta.validation.constraints.Email @Size(max=254)String email,@NotBlank @Pattern(regexp="[0-9]{6}")String code,@NotBlank @Size(max=72)String password){}
 public record Preference(boolean enabled){}
 public record Delete(@NotNull @Min(0)Integer version,@Size(max=72)String password,@NotBlank @Size(max=500)String reason){}
 @PostMapping("/api/account/email-code") public ResultVo<?> code(@RequestAttribute(AuthInterceptor.USER)User u,@Valid @RequestBody Email b){emails.send(b.email(),"BIND",u.getId());return ResultVo.success(null);}
 @PutMapping("/api/account/email") public ResultVo<?> bind(@RequestAttribute(AuthInterceptor.USER)User u,@Valid @RequestBody Bind b){return ResultVo.success(service.bind(u.getId(),b.email(),b.code(),b.password()));}
 @PutMapping("/api/account/mail-notifications") public ResultVo<?> mail(@RequestAttribute(AuthInterceptor.USER)User u,@RequestBody Preference b){service.mailPreference(u.getId(),b.enabled());return ResultVo.success(null);}
 @DeleteMapping("/api/account") public ResultVo<?> delete(@RequestAttribute(AuthInterceptor.USER)User u,@Valid @RequestBody Delete b){service.delete(u.getId(),u.getId(),b.version(),b.password(),b.reason());return ResultVo.success(null);}
 @DeleteMapping("/api/admin/users/{id}") public ResultVo<?> adminDelete(@PathVariable long id,@RequestAttribute(AuthInterceptor.USER)User u,@Valid @RequestBody Delete b){if(id==u.getId())throw new edu.campusloop.common.ApiException(409,"请使用自己的账号设置注销");service.delete(u.getId(),id,b.version(),null,b.reason());return ResultVo.success(null);}
}
