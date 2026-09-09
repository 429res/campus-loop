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
    @org.springframework.beans.factory.annotation.Autowired private edu.campusloop.auth.LoginChallengeService challenges;
    @org.springframework.beans.factory.annotation.Autowired private edu.campusloop.ratelimit.ClientAddressResolver addresses;
    @org.springframework.beans.factory.annotation.Autowired private edu.campusloop.web.user.mapper.UserMapper users;
    @org.springframework.beans.factory.annotation.Autowired private edu.campusloop.web.auth.service.EmailVerificationService emails;
    @org.springframework.beans.factory.annotation.Value("${campus.registration-mode:CLOSED}") private String registrationMode;
    @org.springframework.beans.factory.annotation.Autowired private edu.campusloop.web.auth.service.AccountSecurityAlerts securityAlerts;
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth=auth; }
    @PostMapping("/register") public ResultVo<AuthService.UserInfo> register(@Valid @RequestBody RegisterRequest body) { return ResultVo.success(auth.register(body)); }
    @PostMapping("/login") public ResultVo<AuthService.LoginResult> login(@Valid @RequestBody LoginRequest body,HttpServletRequest request) {
        String ip=addresses.resolve(request),name=body.username().trim().toLowerCase(java.util.Locale.ROOT);
        var u=users.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<User>().eq("username",name).or().eq("email",name));
        String account=u==null?"NAME:"+name:"USER:"+u.getId();
        if(challenges.required(account,ip))challenges.verify(body.captchaId(),body.captchaAnswer(),ip);
        try{var result=auth.login(body);challenges.success(account,ip);securityAlerts.login(result.user().id(),ip);return ResultVo.success(result);}catch(edu.campusloop.common.ApiException e){if(e.getStatus()==401)challenges.failed(account,ip);throw e;}
    }
    public record EmailCode(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Email @jakarta.validation.constraints.Size(max=254) String email){}
    @GetMapping("/captcha") public ResultVo<?> captcha(HttpServletRequest request){return ResultVo.success(challenges.image(addresses.resolve(request)));}
    @GetMapping("/options") public ResultVo<?> options(){return ResultVo.success(java.util.Map.of("registrationMode",registrationMode,"emailAvailable",emails.enabled()));}
    @PostMapping("/email-code") public ResultVo<?> emailCode(@Valid @RequestBody EmailCode body){if(!"EMAIL_VERIFIED".equals(registrationMode))throw new edu.campusloop.common.ApiException(403,"当前未开放邮箱注册");emails.send(body.email(),"REGISTER",0);return ResultVo.success(null);}
    @GetMapping("/me") public ResultVo<AuthService.UserInfo> me(@RequestAttribute(AuthInterceptor.USER) User user) { return ResultVo.success(auth.info(user)); }
    @PatchMapping("/me") public ResultVo<AuthService.UserInfo> updateMe(@RequestAttribute(AuthInterceptor.USER) User user,@Valid @RequestBody UpdateProfileRequest body) { return ResultVo.success(auth.updateProfile(user.getId(),body)); }
    @PostMapping("/password") public ResultVo<Void> changePassword(@RequestAttribute(AuthInterceptor.USER) User user,@Valid @RequestBody ChangePasswordRequest body) { auth.changePassword(user.getId(),body);return ResultVo.success(null); }
    @PostMapping("/logout") public ResultVo<Void> logout(HttpServletRequest request) { auth.logout(AuthInterceptor.token(request)); return ResultVo.success(null); }
}
