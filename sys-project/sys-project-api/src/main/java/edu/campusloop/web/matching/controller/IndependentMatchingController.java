package edu.campusloop.web.matching.controller;

import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.ApiException;
import edu.campusloop.common.ResultVo;
import edu.campusloop.web.matching.service.IndependentMatchingService;
import edu.campusloop.web.user.entity.User;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.MultiValueMap;

@RestController
public class IndependentMatchingController {
    private final IndependentMatchingService matching;

    public IndependentMatchingController(IndependentMatchingService matching) { this.matching = matching; }

    @GetMapping("/api/matches/independent")
    public ResultVo<IndependentMatchingService.Result> list(@RequestAttribute(AuthInterceptor.USER) User user,
                                                           @RequestParam MultiValueMap<String, String> query) {
        if (query.keySet().stream().anyMatch(key -> !"ruleVersion".equals(key)) ||
            (query.containsKey("ruleVersion") && query.get("ruleVersion").size() != 1))
            throw new ApiException(400, "独立需求推荐仅接受一个 ruleVersion 参数，身份由登录会话确定");
        return ResultVo.success(matching.recommendations(user.getId(), query.getFirst("ruleVersion")));
    }
}
