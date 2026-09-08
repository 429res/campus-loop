package edu.campusloop.web.exchange.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.campusloop.web.exchange.entity.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface ExchangeMapper extends BaseMapper<ExchangeRecord> {
    @Select("<script>SELECT p.exchange_id,p.user_id,u.display_name,p.offered_item_id,p.recipient_user_id," +
        "p.confirmed_at,p.handed_off_at,p.received_at FROM cl_exchange_participant p JOIN cl_user u ON u.id=p.user_id " +
        "WHERE p.exchange_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
        "ORDER BY p.exchange_id,p.offered_item_id</script>")
    List<ExchangeParticipantRecord> participants(@Param("ids") List<Long> ids);
}
