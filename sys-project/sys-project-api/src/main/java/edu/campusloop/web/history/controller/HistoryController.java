package edu.campusloop.web.history.controller;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.*;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.history.dto.HistoryRequest;
import edu.campusloop.web.history.service.HistoryService;
import edu.campusloop.web.history.vo.HistoryView;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Set;
@RestController
public class HistoryController {
    private final HistoryService history;
    private final edu.campusloop.web.history.service.HistoryConfirmationService confirmations;
    public HistoryController(HistoryService history,edu.campusloop.web.history.service.HistoryConfirmationService confirmations) {this.history=history;this.confirmations=confirmations;}
    @ModelAttribute public void privateResponse(jakarta.servlet.http.HttpServletResponse response) {response.setHeader("Cache-Control","private, no-store");response.addHeader("Vary","Authorization");}
    @GetMapping("/api/items/{id}/history") public ResultVo<PageResult<HistoryView>> list(@RequestAttribute(value=AuthInterceptor.USER,required=false) User user,@PathVariable long id,@RequestParam MultiValueMap<String,String> params) {
        allowed(params,Set.of("page","size"));return ResultVo.success(history.list(user,id,number(params,"page",1),number(params,"size",12)));
    }
    @GetMapping("/api/items/{id}/history/{eventId}") public ResultVo<HistoryView> detail(@RequestAttribute(value=AuthInterceptor.USER,required=false) User user,@PathVariable long id,@PathVariable long eventId,@RequestParam MultiValueMap<String,String> params) {
        allowed(params,Set.of());return ResultVo.success(history.detail(user,id,eventId));
    }
    @PostMapping("/api/items/{id}/history") public ResultVo<HistoryView> create(@RequestAttribute(AuthInterceptor.USER) User user,@PathVariable long id,@RequestBody HistoryRequest body,@RequestParam MultiValueMap<String,String> params) {
        allowed(params,Set.of());return ResultVo.success(history.detail(user,id,history.create(user.getId(),id,body)));
    }
    @GetMapping("/api/history-evidence/{uploadId}") public ResponseEntity<Resource> evidence(@RequestAttribute(AuthInterceptor.USER) User user,@PathVariable String uploadId,@RequestParam MultiValueMap<String,String> params) {
        allowed(params,Set.of());return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore().cachePrivate())
            .header("X-Content-Type-Options","nosniff").body(new FileSystemResource(history.evidence(user,uploadId)));
    }
    @PostMapping("/api/items/{id}/history/{eventId}/confirmation-request") public ResultVo<HistoryView> requestConfirmation(@RequestAttribute(AuthInterceptor.USER) User user,@PathVariable long id,@PathVariable long eventId,@RequestBody com.fasterxml.jackson.databind.JsonNode body,@RequestParam MultiValueMap<String,String> params) {
        return confirmationAction(user,id,eventId,body,params,"request");
    }
    @PostMapping("/api/items/{id}/history/{eventId}/confirm") public ResultVo<HistoryView> confirm(@RequestAttribute(AuthInterceptor.USER) User user,@PathVariable long id,@PathVariable long eventId,@RequestBody com.fasterxml.jackson.databind.JsonNode body,@RequestParam MultiValueMap<String,String> params) {
        return confirmationAction(user,id,eventId,body,params,"confirm");
    }
    @PostMapping("/api/items/{id}/history/{eventId}/withdraw-confirmation") public ResultVo<HistoryView> withdraw(@RequestAttribute(AuthInterceptor.USER) User user,@PathVariable long id,@PathVariable long eventId,@RequestBody com.fasterxml.jackson.databind.JsonNode body,@RequestParam MultiValueMap<String,String> params) {
        return confirmationAction(user,id,eventId,body,params,"withdraw");
    }
    private ResultVo<HistoryView> confirmationAction(User user,long item,long event,com.fasterxml.jackson.databind.JsonNode body,MultiValueMap<String,String> params,String action) {
        allowed(params,Set.of());
        confirmations.act(user.getId(),item,event,action,edu.campusloop.web.history.dto.HistoryConfirmationCommand.parse(body,action));
        return ResultVo.success(history.detail(user,item,event));
    }
    private static void allowed(MultiValueMap<String,String> params,Set<String> names) {
        if(params.entrySet().stream().anyMatch(e->!names.contains(e.getKey()) || e.getValue().size()!=1)) throw new ApiException(400,"存在不支持或重复的参数");
    }
    private static int number(MultiValueMap<String,String> params,String name,int fallback) {
        String raw=params.getFirst(name);if(raw==null) return fallback;
        if(!raw.matches("[0-9]{1,10}")) throw new ApiException(400,"分页参数须为整数");
        try{return Integer.parseInt(raw);}catch(NumberFormatException bad){throw new ApiException(400,"分页参数超出范围");}
    }
}
