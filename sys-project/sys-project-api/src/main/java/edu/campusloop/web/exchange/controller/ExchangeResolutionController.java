package edu.campusloop.web.exchange.controller;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.ResultVo;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.exchange.dto.ResolveDisputeRequest;
import edu.campusloop.web.exchange.service.*;
import edu.campusloop.web.exchange.mapper.ExchangeResolutionMapper;
import edu.campusloop.web.exchange.vo.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
public class ExchangeResolutionController {
 private final ExchangeLifecycleService lifecycle;private final ExchangeQueryService queries;private final ExchangeResolutionMapper resolutions;
 public ExchangeResolutionController(ExchangeLifecycleService lifecycle,ExchangeQueryService queries,ExchangeResolutionMapper resolutions){this.lifecycle=lifecycle;this.queries=queries;this.resolutions=resolutions;}
 @PostMapping("/api/admin/exchange-disputes/{id}/resolve") public ResultVo<AdminExchangeDetail> resolve(@RequestAttribute(AuthInterceptor.USER)User user,@PathVariable long id,@Valid @RequestBody ResolveDisputeRequest body){lifecycle.resolve(user.getId(),id,body);return ResultVo.success(queries.adminDetail(id));}
 @GetMapping("/api/admin/exchanges/{id}/resolutions") public ResultVo<List<ResolutionView>> admin(@PathVariable long id){queries.adminDetail(id);return ResultVo.success(resolutions.all(id));}
 @GetMapping("/api/exchanges/{id}/resolutions") public ResultVo<List<ResolutionView>> mine(@RequestAttribute(AuthInterceptor.USER)User user,@PathVariable long id){queries.detail(user.getId(),id);return ResultVo.success(resolutions.all(id));}
}
