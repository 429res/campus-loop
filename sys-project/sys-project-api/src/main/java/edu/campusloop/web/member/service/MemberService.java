package edu.campusloop.web.member.service;
import edu.campusloop.common.ApiException;
import edu.campusloop.web.user.mapper.UserMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class MemberService {
 public record Profile(long id,String displayName,String avatarUrl,String bio,String campus,long itemCount,long exchangeCount,String coverUrl){}
 private final UserMapper users;private final JdbcTemplate db;
 public MemberService(UserMapper users,JdbcTemplate db){this.users=users;this.db=db;}
 public Profile profile(long id){
  var u=users.selectById(id);if(u==null||!"ACTIVE".equals(u.getStatus()))throw new ApiException(404,"主页不存在或暂不可见");
  long items=db.queryForObject("SELECT COUNT(*) FROM cl_item WHERE owner_id=? AND status IN ('AVAILABLE','RESERVED')",Long.class,id);
  long exchanges=db.queryForObject("SELECT COUNT(*) FROM cl_exchange_participant p JOIN cl_exchange e ON e.id=p.exchange_id WHERE p.user_id=? AND e.status='COMPLETED'",Long.class,id);
  return new Profile(id,u.getDisplayName(),u.getAvatarUrl(),u.getBio(),u.getCampus(),items,exchanges,u.getCoverUrl());
 }
}
