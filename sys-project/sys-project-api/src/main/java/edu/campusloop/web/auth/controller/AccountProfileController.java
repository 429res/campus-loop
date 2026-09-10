package edu.campusloop.web.auth.controller;
import edu.campusloop.auth.*;
import edu.campusloop.common.ResultVo;
import edu.campusloop.web.auth.dto.AccountProfileRequest;
import edu.campusloop.web.auth.service.AccountProfileService;
import edu.campusloop.web.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/account/profile")
public class AccountProfileController {
    private final AccountProfileService service;private final AuthService auth;
    public AccountProfileController(AccountProfileService service,AuthService auth){this.service=service;this.auth=auth;}
    @GetMapping public ResultVo<AuthService.UserInfo> get(@RequestAttribute(AuthInterceptor.USER) User user){return ResultVo.success(auth.info(user));}
    @PutMapping public ResultVo<AuthService.UserInfo> save(@RequestAttribute(AuthInterceptor.USER) User user,@Valid @RequestBody AccountProfileRequest body){return ResultVo.success(service.save(user,body));}
}
