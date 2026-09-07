package edu.campusloop.web.matching.controller;
import edu.campusloop.common.ResultVo;
import edu.campusloop.matching.CycleMatcher;
import edu.campusloop.web.matching.service.MatchingService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
public class MatchingController {
    private final MatchingService matching;
    public MatchingController(MatchingService matching){this.matching=matching;}
    @GetMapping("/api/matches") public ResultVo<List<CycleMatcher.Recommendation>> list(){return ResultVo.success(matching.recommendations());}
}
