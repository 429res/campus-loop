package edu.campusloop.web.spotlight.controller;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.ResultVo;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.spotlight.service.SpotlightService;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
@RestController public class SpotlightController {
 private final SpotlightService service;public SpotlightController(SpotlightService service){this.service=service;}
 public record Setting(boolean enabled,@Min(0) @Max(999)int sortOrder){}
 @GetMapping("/api/spotlights")public ResultVo<?> list(){return ResultVo.success(service.visible());}
 @GetMapping("/api/admin/items/spotlights")public ResultVo<?> settings(){return ResultVo.success(service.settings());}
 @PutMapping("/api/admin/items/{id}/spotlight")public ResultVo<?> save(@PathVariable long id,@RequestAttribute(AuthInterceptor.USER)User u,@Valid @RequestBody Setting b){service.save(u.getId(),id,b.enabled(),b.sortOrder());return ResultVo.success(null);}
}
