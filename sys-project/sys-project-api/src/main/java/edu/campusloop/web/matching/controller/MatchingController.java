package edu.campusloop.web.matching.controller;
import edu.campusloop.common.ResultVo;
import edu.campusloop.common.ApiException;
import edu.campusloop.matching.CycleMatcher;
import edu.campusloop.web.matching.service.MatchingService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
public class MatchingController {
    private final MatchingService matching;
    public MatchingController(MatchingService matching){this.matching=matching;}
    @GetMapping("/api/matches") public ResultVo<List<CycleMatcher.Recommendation>> list(@RequestParam(required=false) String ruleVersion){
        if(ruleVersion!=null && !"legacy-v1".equals(ruleVersion)) throw new ApiException(400,"不支持的旧版匹配规则版本；独立需求请使用 /api/matches/independent");
        return ResultVo.success(matching.recommendations());
    }
}
