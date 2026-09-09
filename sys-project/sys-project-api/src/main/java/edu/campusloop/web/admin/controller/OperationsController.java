package edu.campusloop.web.admin.controller;
import edu.campusloop.common.*;
import edu.campusloop.web.admin.service.OperationsService;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/admin/operations")
public class OperationsController {
 private final OperationsService service;
 public OperationsController(OperationsService service){this.service=service;}
 @GetMapping("/summary")public ResultVo<OperationsService.Summary> summary(@RequestParam(defaultValue="30")int days){return ResultVo.success(service.summary(days));}
 @GetMapping("/audits")public ResultVo<PageResult<OperationsService.Audit>> audits(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)String module){return ResultVo.success(service.audits(page,size,module));}
}
