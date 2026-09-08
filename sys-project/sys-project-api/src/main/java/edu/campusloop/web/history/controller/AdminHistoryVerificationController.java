package edu.campusloop.web.history.controller;
import com.fasterxml.jackson.databind.JsonNode;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.*;
import edu.campusloop.web.history.dto.HistoryVerificationCommand;
import edu.campusloop.web.history.service.HistoryVerificationService;
import edu.campusloop.web.user.entity.User;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Set;
@RestController
@RequestMapping("/api/admin/history-verifications")
public class AdminHistoryVerificationController {
    private final HistoryVerificationService service;
    public AdminHistoryVerificationController(HistoryVerificationService service) {this.service=service;}
    @ModelAttribute public void privateResponse(jakarta.servlet.http.HttpServletResponse response) {response.setHeader("Cache-Control","private, no-store");response.addHeader("Vary","Authorization");}
    @GetMapping public ResultVo<PageResult<JsonNode>> page(@RequestAttribute(AuthInterceptor.USER) User user,@RequestParam MultiValueMap<String,String> params) {
        allowed(params,Set.of("page","size","status"));return ResultVo.success(service.page(user.getId(),number(params,"page",1),number(params,"size",12),params.getFirst("status")));
    }
    @GetMapping("/{id}") public ResultVo<JsonNode> detail(@RequestAttribute(AuthInterceptor.USER) User user,@PathVariable long id,@RequestParam MultiValueMap<String,String> params) {allowed(params,Set.of());return ResultVo.success(service.detail(user.getId(),id));}
    @PostMapping("/{id}/decision") public ResultVo<JsonNode> decide(@RequestAttribute(AuthInterceptor.USER) User user,@PathVariable long id,@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> params) {
        allowed(params,Set.of());service.decide(user.getId(),id,HistoryVerificationCommand.parse(body));return ResultVo.success(service.detail(user.getId(),id));
    }
    private static void allowed(MultiValueMap<String,String> params,Set<String> names) {if(params.entrySet().stream().anyMatch(e->!names.contains(e.getKey()) || e.getValue().size()!=1)) throw new ApiException(400,"不支持或重复的查询参数");}
    private static int number(MultiValueMap<String,String> params,String name,int fallback) {String value=params.getFirst(name);if(value==null)return fallback;try {if(!value.matches("[0-9]{1,10}")) throw new NumberFormatException();return Integer.parseInt(value);}catch(NumberFormatException bad){throw new ApiException(400,"分页参数不正确");}}
}
