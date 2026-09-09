package edu.campusloop.web.admin.service;
import edu.campusloop.common.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
@Service @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class OperationsService {
 private final JdbcTemplate db;
 public OperationsService(JdbcTemplate db){this.db=db;}
 public record Day(String date,long users,long items,long completedExchanges){}
 public record CategoryCount(long id,String name,long items){}
 public record Summary(int days,LocalDateTime generatedAt,Map<String,Long> totals,List<Day> trend,List<CategoryCount> categories){}
 public record Audit(String key,String module,String action,long subjectId,Long actorId,String actorName,String reason,LocalDateTime createdAt){}
 public Summary summary(int days){
  if(!Set.of(7,30,90).contains(days))throw new ApiException(400,"统计区间为7、30或90天");
  LocalDate end=LocalDate.now(ZoneOffset.UTC),start=end.minusDays(days-1);
  var users=series("cl_user","created_at","",start);var items=series("cl_item","created_at","",start);
  var exchanges=series("cl_exchange_event","occurred_at"," AND new_status='COMPLETED'",start);
  List<Day> trend=new ArrayList<>();for(var date=start;!date.isAfter(end);date=date.plusDays(1)){String key=date.toString();trend.add(new Day(key,users.getOrDefault(key,0L),items.getOrDefault(key,0L),exchanges.getOrDefault(key,0L)));}
  Map<String,Long> totals=new LinkedHashMap<>();
  totals.put("users",count("cl_user",""));totals.put("activeUsers",count("cl_user","status='ACTIVE'"));totals.put("items",count("cl_item",""));totals.put("pendingReview",count("cl_item","status='PENDING_REVIEW'"));
  totals.put("availableItems",count("cl_item","status='AVAILABLE'"));totals.put("completedExchanges",count("cl_exchange","status='COMPLETED'"));totals.put("activeExchanges",count("cl_exchange","status IN ('AWAITING_CONFIRMATION','READY')"));
  totals.put("disputedExchanges",count("cl_exchange","status='DISPUTED'"));totals.put("openReports",count("cl_report","status IN ('SUBMITTED','IN_REVIEW')"));totals.put("activeDemands",count("cl_demand","status='ACTIVE'"));
  var categories=db.query("SELECT c.id,c.name,COUNT(i.id) AS amount FROM cl_category c LEFT JOIN cl_item i ON i.category_id=c.id GROUP BY c.id,c.name ORDER BY amount DESC,c.id",(rs,n)->new CategoryCount(rs.getLong("id"),rs.getString("name"),rs.getLong("amount")));
  return new Summary(days,LocalDateTime.now(ZoneOffset.UTC),totals,trend,categories);
 }
 private long count(String table,String where){return db.queryForObject("SELECT COUNT(*) FROM "+table+(where.isEmpty()?"":" WHERE "+where),Long.class);}
 private Map<String,Long> series(String table,String field,String filter,LocalDate start){
  Map<String,Long> result=new HashMap<>();db.query("SELECT CAST("+field+" AS DATE) AS activity_date,COUNT(*) AS amount FROM "+table+" WHERE "+field+">=? AND "+field+"<?"+filter+" GROUP BY CAST("+field+" AS DATE)",rs->{result.put(rs.getDate("activity_date").toLocalDate().toString(),rs.getLong("amount"));},start.atStartOfDay(),LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay());return result;
 }
 private static final String AUDITS="SELECT CONCAT('review-',id) AS audit_key,'ITEMS' AS module,action,item_id AS subject_id,operator_user_id AS actor_id,reason,created_at FROM cl_item_review_audit UNION ALL "
  +"SELECT CONCAT('report-',id),'REPORTS',action,report_id,actor_user_id,reason,created_at FROM cl_report_audit UNION ALL "
  +"SELECT CONCAT('exchange-',id),'EXCHANGES',event_type,exchange_id,actor_id,reason,occurred_at FROM cl_exchange_event UNION ALL "
  +"SELECT CONCAT('status-',id),'USERS',CONCAT('STATUS_',new_status),target_user_id,operator_user_id,reason,created_at FROM cl_user_status_audit UNION ALL "
  +"SELECT CONCAT('access-',id),'USERS','ACCESS_CHANGED',target_user_id,actor_user_id,reason,created_at FROM cl_user_access_audit";
 public PageResult<Audit> audits(int page,int size,String module){
  if(page<1||size<1||size>100||module!=null&&!Set.of("ITEMS","REPORTS","EXCHANGES","USERS").contains(module))throw new ApiException(400,"分页或模块参数不正确");
  String where=module==null?"":" WHERE a.module=?";List<Object> args=new ArrayList<>();if(module!=null)args.add(module);
  Long total=db.queryForObject("SELECT COUNT(*) FROM ("+AUDITS+") a"+where,Long.class,args.toArray());
  args.add(size);args.add(((long)page-1)*size);
  var records=db.query("SELECT a.*,u.display_name FROM ("+AUDITS+") a LEFT JOIN cl_user u ON u.id=a.actor_id"+where+" ORDER BY a.created_at DESC,a.audit_key DESC LIMIT ? OFFSET ?",(rs,n)->new Audit(rs.getString("audit_key"),rs.getString("module"),rs.getString("action"),rs.getLong("subject_id"),rs.getObject("actor_id",Long.class),rs.getString("display_name"),rs.getString("reason"),rs.getObject("created_at",LocalDateTime.class)),args.toArray());
  return new PageResult<>(records,total,page,size);
 }
}
