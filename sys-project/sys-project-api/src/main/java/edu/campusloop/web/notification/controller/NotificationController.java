package edu.campusloop.web.notification.controller;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.*;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.notification.entity.Notification;
import edu.campusloop.web.notification.service.NotificationService;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/notifications")
public class NotificationController {
 private final NotificationService service;
 public NotificationController(NotificationService service){this.service=service;}
 @GetMapping public ResultVo<PageResult<Notification>> page(@RequestAttribute(AuthInterceptor.USER) User user,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="false") boolean unread){return ResultVo.success(service.page(user.getId(),page,size,unread));}
 @GetMapping("/unread-count") public ResultVo<Long> unread(@RequestAttribute(AuthInterceptor.USER) User user){return ResultVo.success(service.unread(user.getId()));}
 @PatchMapping("/{id}/read") public ResultVo<Void> read(@RequestAttribute(AuthInterceptor.USER) User user,@PathVariable long id){service.read(user.getId(),id);return ResultVo.success(null);}
 @PostMapping("/read-all") public ResultVo<Void> readAll(@RequestAttribute(AuthInterceptor.USER) User user){service.readAll(user.getId());return ResultVo.success(null);}
}
