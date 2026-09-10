package edu.campusloop.web.community.controller;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.ResultVo;
import edu.campusloop.web.community.service.CommunityService;
import edu.campusloop.web.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/community/posts")
public class CommunityController {
 private final CommunityService service;public CommunityController(CommunityService service){this.service=service;}
 @GetMapping public ResultVo<?> page(@RequestAttribute(value=AuthInterceptor.USER,required=false)User user,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="false")boolean mine,@RequestParam(required=false)String keyword){return ResultVo.success(service.page(user==null?null:user.getId(),page,size,mine,false,false,keyword));}
 @GetMapping("/{id}") public ResultVo<?> detail(@PathVariable long id,@RequestAttribute(value=AuthInterceptor.USER,required=false)User user){return ResultVo.success(service.detail(id,user==null?null:user.getId(),false));}
 @PostMapping public ResultVo<?> create(@RequestAttribute(AuthInterceptor.USER)User user,@Valid @RequestBody CommunityService.Publish body){return ResultVo.success(service.publish(user.getId(),body));}
 @DeleteMapping("/{id}") public ResultVo<?> withdraw(@PathVariable long id,@RequestAttribute(AuthInterceptor.USER)User user,@Valid @RequestBody CommunityService.Version body){service.withdraw(user.getId(),id,body.version());return ResultVo.success(null);}
 @PutMapping("/{id}/like") public ResultVo<?> like(@PathVariable long id,@RequestAttribute(AuthInterceptor.USER)User user){service.like(user.getId(),id,true);return ResultVo.success(null);}
 @DeleteMapping("/{id}/like") public ResultVo<?> unlike(@PathVariable long id,@RequestAttribute(AuthInterceptor.USER)User user){service.like(user.getId(),id,false);return ResultVo.success(null);}
 @GetMapping("/{id}/replies") public ResultVo<?> replies(@PathVariable long id,@RequestAttribute(value=AuthInterceptor.USER,required=false)User user,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ResultVo.success(service.replies(id,user==null?null:user.getId(),page,size,false));}
 @PostMapping("/{id}/replies") public ResultVo<?> reply(@PathVariable long id,@RequestAttribute(AuthInterceptor.USER)User user,@Valid @RequestBody CommunityService.ReplyRequest body){return ResultVo.success(service.reply(user.getId(),id,body));}
 @DeleteMapping("/{id}/replies/{replyId}") public ResultVo<?> deleteReply(@PathVariable long id,@PathVariable long replyId,@RequestAttribute(AuthInterceptor.USER)User user){service.deleteReply(user.getId(),id,replyId);return ResultVo.success(null);}
 @PostMapping("/{id}/reports") public ResultVo<?> report(@PathVariable long id,@RequestAttribute(AuthInterceptor.USER)User user,@Valid @RequestBody CommunityService.Reason body){service.report(user.getId(),id,body);return ResultVo.success(null);}
}
