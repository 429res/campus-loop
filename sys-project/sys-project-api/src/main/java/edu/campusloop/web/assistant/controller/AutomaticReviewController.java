package edu.campusloop.web.assistant.controller;
import edu.campusloop.common.ResultVo;
import edu.campusloop.web.assistant.service.AutomaticReviewService;
import org.springframework.web.bind.annotation.*;
@RestController
public class AutomaticReviewController {
 private final AutomaticReviewService service;
 public AutomaticReviewController(AutomaticReviewService service){this.service=service;}
 @GetMapping("/api/admin/items/auto-review-queue")public ResultVo<?> items(){return ResultVo.success(service.queue("ITEM"));}
 @GetMapping("/api/admin/reports/auto-review-queue")public ResultVo<?> reports(){return ResultVo.success(service.queue("REPORT"));}
 @GetMapping("/api/admin/community/posts/auto-review-queue")public ResultVo<?> posts(){return ResultVo.success(service.queue("COMMUNITY"));}
 @GetMapping("/api/admin/items/{id}/auto-review")public ResultVo<?> item(@PathVariable long id){return ResultVo.success(service.status("ITEM",id));}
 @GetMapping("/api/admin/reports/{id}/auto-review")public ResultVo<?> report(@PathVariable long id){return ResultVo.success(service.status("REPORT",id));}
 @GetMapping("/api/admin/community/posts/{id}/auto-review")public ResultVo<?> post(@PathVariable long id){return ResultVo.success(service.status("COMMUNITY",id));}
}
