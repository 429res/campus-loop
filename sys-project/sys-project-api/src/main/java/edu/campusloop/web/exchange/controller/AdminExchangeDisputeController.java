package edu.campusloop.web.exchange.controller;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.*;
import edu.campusloop.web.exchange.service.ExchangeDisputeQueryService;
import edu.campusloop.web.exchange.vo.*;
import edu.campusloop.web.user.entity.User;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Set;
@RestController
@RequestMapping("/api/admin/exchange-disputes")
public class AdminExchangeDisputeController {
    private final ExchangeDisputeQueryService queries;
    public AdminExchangeDisputeController(ExchangeDisputeQueryService queries) {this.queries=queries;}
    @ModelAttribute public void privateResponse(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","private, no-store");response.addHeader("Vary","Authorization");
    }
    @GetMapping public ResultVo<PageResult<ExchangeView>> page(@RequestAttribute(AuthInterceptor.USER) User user,
        @RequestParam MultiValueMap<String,String> params) {
        allowed(params,Set.of("page","size"));return ResultVo.success(queries.page(user.getId(),number(params,"page",1),number(params,"size",12)));
    }
    @GetMapping("/{id}") public ResultVo<ExchangeView> detail(@RequestAttribute(AuthInterceptor.USER) User user,
        @PathVariable long id,@RequestParam MultiValueMap<String,String> params) {
        allowed(params,Set.of());return ResultVo.success(queries.detail(user.getId(),id));
    }
    @GetMapping("/{id}/events") public ResultVo<PageResult<ExchangeEventView>> events(@RequestAttribute(AuthInterceptor.USER) User user,
        @PathVariable long id,@RequestParam MultiValueMap<String,String> params) {
        allowed(params,Set.of("page","size"));return ResultVo.success(queries.events(user.getId(),id,number(params,"page",1),number(params,"size",12)));
    }
    private static void allowed(MultiValueMap<String,String> params,Set<String> names) {
        if(params.entrySet().stream().anyMatch(e->!names.contains(e.getKey()) || e.getValue().size()!=1)) throw new ApiException(400,"不支持或重复的查询参数");
    }
    private static int number(MultiValueMap<String,String> params,String name,int fallback) {
        String value=params.getFirst(name);if(value==null)return fallback;
        try {if(!value.matches("[0-9]{1,10}")) throw new NumberFormatException();return Integer.parseInt(value);}
        catch(NumberFormatException bad){throw new ApiException(400,"分页参数不正确");}
    }
}
