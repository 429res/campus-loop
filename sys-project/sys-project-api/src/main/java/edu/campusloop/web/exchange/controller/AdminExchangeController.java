package edu.campusloop.web.exchange.controller;

import edu.campusloop.common.*;
import edu.campusloop.web.exchange.service.ExchangeQueryService;
import edu.campusloop.web.exchange.vo.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

/** AuthInterceptor requires ADMIN for this separate, read-only route. */
@RestController
@RequestMapping("/api/admin/exchanges")
public class AdminExchangeController {
    private final ExchangeQueryService queries;
    public AdminExchangeController(ExchangeQueryService queries) { this.queries=queries; }
    @GetMapping public ResultVo<PageResult<ExchangeView>> page(@RequestParam MultiValueMap<String,String> params) {
        allow(params,Set.of("page","size","status"));
        return ResultVo.success(queries.adminPage(integer(params,"page",1),integer(params,"size",12),params.getFirst("status")));
    }
    @GetMapping("/{id}") public ResultVo<AdminExchangeDetail> detail(@PathVariable long id,@RequestParam MultiValueMap<String,String> params) {
        allow(params,Set.of());
        return ResultVo.success(queries.adminDetail(id));
    }
    private static void allow(MultiValueMap<String,String> params,Set<String> names) {
        if (params.entrySet().stream().anyMatch(entry -> !names.contains(entry.getKey()) || entry.getValue().size()!=1))
            throw new ApiException(400,"存在不支持或重复的参数");
    }
    private static int integer(MultiValueMap<String,String> params,String key,int fallback) {
        String value=params.getFirst(key);
        if (value==null) return fallback;
        if (!value.matches("[0-9]{1,10}")) throw new ApiException(400,"分页参数须为整数");
        try { return Integer.parseInt(value); }
        catch (NumberFormatException exception) { throw new ApiException(400,"分页参数超出范围"); }
    }
}
