package edu.campusloop.web.exchange.mapper;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
public interface ExchangeDisputeQueryMapper {
    record Event(long id,long exchangeId,Long actorId,String eventType,String previousStatus,String newStatus,
                 int previousVersion,int newVersion,String reason,LocalDateTime occurredAt) {}
    @Select("SELECT id,exchange_id,actor_id,event_type,previous_status,new_status,previous_version,new_version," +
        "CASE WHEN event_type='DISPUTED' THEN reason ELSE NULL END AS reason,occurred_at " +
        "FROM cl_exchange_event WHERE exchange_id=#{id} ORDER BY new_version,id LIMIT #{size} OFFSET #{offset}")
    List<Event> page(@Param("id") long id,@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT COUNT(*) FROM cl_exchange_event WHERE exchange_id=#{id}")
    long count(@Param("id") long id);
}
