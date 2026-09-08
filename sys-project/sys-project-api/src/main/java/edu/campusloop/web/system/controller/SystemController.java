package edu.campusloop.web.system.controller;
import edu.campusloop.common.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
public class SystemController {
    private final JdbcTemplate jdbc;
    public SystemController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @GetMapping("/api/health") public ResultVo<Map<String,String>> health(){jdbc.queryForObject("SELECT 1",Integer.class);return ResultVo.success(Map.of("status","UP","database","UP"));}
    @PostMapping("/api/exchanges/{id}/handoff")
    public ResultVo<Void> pending(){throw new ApiException(501,"正式交换流程待开发；推荐不会占用物品");}
}
