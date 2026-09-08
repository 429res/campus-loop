package edu.campusloop.web.auth.controller;
import edu.campusloop.common.ResultVo;
import edu.campusloop.auth.*;
import edu.campusloop.web.auth.dto.LoginRequest;
import edu.campusloop.web.auth.dto.RegisterRequest;
import edu.campusloop.web.auth.dto.ChangePasswordRequest;
import edu.campusloop.web.auth.dto.UpdateProfileRequest;
import edu.campusloop.web.user.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth=auth; }
    @PostMapping("/register") public ResultVo<AuthService.UserInfo> register(@Valid @RequestBody RegisterRequest body) { return ResultVo.success(auth.register(body)); }
    @PostMapping("/login") public ResultVo<AuthService.LoginResult> login(@Valid @RequestBody LoginRequest body) { return ResultVo.success(auth.login(body)); }
    @GetMapping("/me") public ResultVo<AuthService.UserInfo> me(@RequestAttribute(AuthInterceptor.USER) User user) { return ResultVo.success(auth.info(user)); }
    @PatchMapping("/me") public ResultVo<AuthService.UserInfo> updateMe(@RequestAttribute(AuthInterceptor.USER) User user,@Valid @RequestBody UpdateProfileRequest body) { return ResultVo.success(auth.updateProfile(user.getId(),body)); }
    @PostMapping("/password") public ResultVo<Void> changePassword(@RequestAttribute(AuthInterceptor.USER) User user,@Valid @RequestBody ChangePasswordRequest body) { auth.changePassword(user.getId(),body);return ResultVo.success(null); }
    @PostMapping("/logout") public ResultVo<Void> logout(HttpServletRequest request) { auth.logout(AuthInterceptor.token(request)); return ResultVo.success(null); }
}
