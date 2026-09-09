package edu.campusloop.web.community.controller;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.ResultVo;
import edu.campusloop.web.community.service.CommunityService;
import edu.campusloop.web.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/admin/community/posts")
public class CommunityAdminController {
 private final CommunityService service;public CommunityAdminController(CommunityService service){this.service=service;}
 @GetMapping public ResultVo<?> page(@RequestAttribute(AuthInterceptor.USER)User user,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="false")boolean reported,@RequestParam(required=false)String keyword){return ResultVo.success(service.page(user.getId(),page,size,false,true,reported,keyword));}
 @GetMapping("/{id}") public ResultVo<?> detail(@PathVariable long id,@RequestAttribute(AuthInterceptor.USER)User user){return ResultVo.success(service.detail(id,user.getId(),true));}
 @GetMapping("/{id}/reports") public ResultVo<?> reports(@PathVariable long id){return ResultVo.success(service.reports(id));}
 @GetMapping("/{id}/audits") public ResultVo<?> audits(@PathVariable long id){return ResultVo.success(service.audits(id));}
 @GetMapping("/{id}/replies") public ResultVo<?> replies(@PathVariable long id,@RequestAttribute(AuthInterceptor.USER)User user,@RequestParam(defaultValue="1")int page){return ResultVo.success(service.replies(id,user.getId(),page,20,true));}
 @PostMapping("/{id}/moderate") public ResultVo<?> moderate(@PathVariable long id,@RequestAttribute(AuthInterceptor.USER)User user,@Valid @RequestBody CommunityService.Moderate body){service.moderate(user.getId(),id,body);return ResultVo.success(null);}
}
