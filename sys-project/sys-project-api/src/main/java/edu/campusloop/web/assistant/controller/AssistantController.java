package edu.campusloop.web.assistant.controller;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.*;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.assistant.service.QwenAgentService;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
@RestController public class AssistantController {
 private final QwenAgentService agent;private final JdbcTemplate db;
 public AssistantController(QwenAgentService agent,JdbcTemplate db){this.agent=agent;this.db=db;}
 public record Draft(@NotBlank @Size(max=2500)String description){}
 private Object categories(){return db.queryForList("SELECT id,name FROM cl_category WHERE status='ACTIVE'");}
 @GetMapping("/api/assistant/status") public ResultVo<?> status(){return ResultVo.success(Map.of("enabled",agent.enabled()));}
 @PostMapping("/api/assistant/item-draft") public ResultVo<?> draft(@RequestAttribute(AuthInterceptor.USER)User u,@Valid @RequestBody Draft b){return ResultVo.success(agent.assist(u.getId(),"辅助物品发布，按真实描述整理草稿，未知信息列入 checks",b.description(),Map.of("categories",categories())));}
 @PostMapping("/api/admin/items/{id}/assist-review")public ResultVo<?> item(@PathVariable long id,@RequestAttribute(AuthInterceptor.USER)User u){var rows=db.queryForList("SELECT title,description,tags_json,condition_level,status,version FROM cl_item WHERE id=?",id);if(rows.isEmpty())throw new ApiException(404,"物品不存在");return ResultVo.success(agent.assist(u.getId(),"辅助审核物品，无权作出审核决定","评估描述完整性、违规风险与需要核验的事实。",Map.of("item",rows.get(0),"categories",categories())));}
 @PostMapping("/api/admin/community/posts/{id}/assist-review")public ResultVo<?> post(@PathVariable long id,@RequestAttribute(AuthInterceptor.USER)User u){var rows=db.queryForList("SELECT body,status,version FROM cl_community_post WHERE id=?",id);if(rows.isEmpty())throw new ApiException(404,"动态不存在");return ResultVo.success(agent.assist(u.getId(),"辅助动态及举报审核","分析待审核动态与举报，区分指控和已证实事实。",Map.of("post",rows.get(0),"reports",db.queryForList("SELECT reason,status FROM cl_community_report WHERE post_id=? ORDER BY id DESC LIMIT 30",id))));}
 @PostMapping("/api/admin/reports/{id}/assist-review")public ResultVo<?> report(@PathVariable long id,@RequestAttribute(AuthInterceptor.USER)User u){var rows=db.queryForList("SELECT target_summary,reason,status FROM cl_report WHERE id=?",id);if(rows.isEmpty())throw new ApiException(404,"举报不存在");return ResultVo.success(agent.assist(u.getId(),"辅助物品举报审核","区分举报指控与事实，列出需要人工验证的证据。",Map.of("report",rows.get(0))));}
}
