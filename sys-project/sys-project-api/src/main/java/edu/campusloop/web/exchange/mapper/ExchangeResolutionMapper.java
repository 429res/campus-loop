package edu.campusloop.web.exchange.mapper;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
import edu.campusloop.web.exchange.vo.ResolutionView;
public interface ExchangeResolutionMapper {
 @Select("SELECT * FROM cl_exchange_resolution WHERE exchange_id=#{id} ORDER BY previous_version") List<ResolutionView> all(long id);
 @Select("SELECT * FROM cl_exchange_resolution WHERE exchange_id=#{id} AND previous_version=#{version}") ResolutionView previous(@Param("id")long id,@Param("version")int version);
 @Insert("INSERT INTO cl_exchange_resolution(exchange_id,actor_user_id,decision,reason,return_confirmed,previous_version,new_version,created_at) VALUES(#{id},#{actor},#{decision},#{reason},#{returned},#{version},#{version}+1,#{now})")
 int append(@Param("id")long id,@Param("actor")long actor,@Param("decision")String decision,@Param("reason")String reason,@Param("returned")boolean returned,@Param("version")int version,@Param("now")LocalDateTime now);
 @Update("UPDATE cl_exchange SET status=#{next},version=version+1,expires_at=#{deadline},expiry_retry_at=NULL,expiry_failure_code=NULL WHERE id=#{id} AND status='DISPUTED' AND version=#{version}")
 int resolve(@Param("id")long id,@Param("version")int version,@Param("next")String next,@Param("deadline")LocalDateTime deadline);
 @Update("UPDATE cl_item_hold SET expires_at=#{deadline} WHERE exchange_id=#{id}") int extend(@Param("id")long id,@Param("deadline")LocalDateTime deadline);
}
