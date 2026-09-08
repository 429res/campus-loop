package edu.campusloop.web.exchange.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.campusloop.web.exchange.entity.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface ExchangeMapper extends BaseMapper<ExchangeRecord> {
    @Select("<script>SELECT p.exchange_id,p.user_id,u.display_name,p.offered_item_id,p.recipient_user_id," +
        "p.confirmed_at,p.handed_off_at,p.received_at,p.handed_off_note,p.received_note FROM cl_exchange_participant p JOIN cl_user u ON u.id=p.user_id " +
        "WHERE p.exchange_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
        "ORDER BY p.exchange_id,p.offered_item_id</script>")
    List<ExchangeParticipantRecord> participants(@Param("ids") List<Long> ids);

    @Select("SELECT e.id,e.event_type,e.actor_id,u.display_name AS actor_display_name,e.previous_status,e.new_status," +
        "e.previous_version,e.new_version,e.reason,e.occurred_at FROM cl_exchange_event e " +
        "LEFT JOIN cl_user u ON u.id=e.actor_id WHERE e.exchange_id=#{id} ORDER BY e.new_version,e.id")
    List<ExchangeEventRecord> events(@Param("id") long id);
}
