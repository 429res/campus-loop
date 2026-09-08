package edu.campusloop.web.exchange.controller;

import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.*;
import edu.campusloop.web.exchange.dto.CreateExchangeRequest;
import edu.campusloop.web.exchange.service.*;
import edu.campusloop.web.exchange.vo.ExchangeView;
import edu.campusloop.web.user.entity.User;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
@RequestMapping("/api/exchanges")
public class ExchangeController {
    private final ExchangeApplicationService commands;
    private final ExchangeQueryService queries;
    public ExchangeController(ExchangeApplicationService commands,ExchangeQueryService queries) { this.commands=commands;this.queries=queries; }
    @PostMapping
    public ResultVo<ExchangeView> create(@RequestAttribute(AuthInterceptor.USER) User user,
                                         @RequestBody CreateExchangeRequest body,@RequestParam MultiValueMap<String,String> params) {
        allow(params,Set.of());
        return ResultVo.success(commands.create(user.getId(),body.command()));
    }
    @GetMapping("/mine")
    public ResultVo<PageResult<ExchangeView>> mine(@RequestAttribute(AuthInterceptor.USER) User user,
                                                  @RequestParam MultiValueMap<String,String> params) {
        allow(params,Set.of("page","size","status"));
        return ResultVo.success(queries.mine(user.getId(),integer(params,"page",1),integer(params,"size",12),params.getFirst("status")));
    }
    @GetMapping("/{id}")
    public ResultVo<ExchangeView> detail(@RequestAttribute(AuthInterceptor.USER) User user,@PathVariable long id,
                                         @RequestParam MultiValueMap<String,String> params) {
        allow(params,Set.of());
        return ResultVo.success(queries.detail(user.getId(),id));
    }
    private static void allow(MultiValueMap<String,String> params,Set<String> names) {
        if (params.entrySet().stream().anyMatch(e -> !names.contains(e.getKey()) || e.getValue().size()!=1))
            throw new ApiException(400,"存在不支持或重复的参数");
    }
    private static int integer(MultiValueMap<String,String> params,String key,int fallback) {
        String value=params.getFirst(key);
        if (value==null) return fallback;
        if (!value.matches("[0-9]{1,10}")) throw new ApiException(400,"分页参数须为整数");
        try { return Integer.parseInt(value); } catch(NumberFormatException exception) { throw new ApiException(400,"分页参数超出范围"); }
    }
}
