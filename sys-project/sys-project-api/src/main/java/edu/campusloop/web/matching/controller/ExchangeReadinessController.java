package edu.campusloop.web.matching.controller;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.ResultVo;
import edu.campusloop.web.user.entity.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
public class ExchangeReadinessController {
 private final JdbcTemplate db;
 public ExchangeReadinessController(JdbcTemplate db){this.db=db;}
 @GetMapping("/api/matches/readiness")
 public ResultVo<Map<String,Long>> get(@RequestAttribute(AuthInterceptor.USER) User user){
  Map<String,Long> counts=new LinkedHashMap<>();
  for(String status:List.of("PENDING_REVIEW","AVAILABLE","RESERVED","REJECTED","HIDDEN"))
   counts.put(status,db.queryForObject("SELECT COUNT(*) FROM cl_item WHERE owner_id=? AND status=?",Long.class,user.getId(),status));
  counts.put("DEMANDS",db.queryForObject("SELECT COUNT(*) FROM cl_demand WHERE owner_id=? AND status='ACTIVE'",Long.class,user.getId()));
  return ResultVo.success(counts);
 }
}
