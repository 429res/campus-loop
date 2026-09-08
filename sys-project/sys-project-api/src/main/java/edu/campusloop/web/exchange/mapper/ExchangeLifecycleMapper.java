package edu.campusloop.web.exchange.mapper;

import edu.campusloop.web.exchange.entity.ExchangeRecord;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

public interface ExchangeLifecycleMapper {
    @Select("SELECT * FROM cl_exchange WHERE id=#{id} FOR UPDATE")
    ExchangeRecord lock(long id);
    @Select("SELECT * FROM cl_exchange WHERE id=#{id} FOR UPDATE SKIP LOCKED")
    ExchangeRecord tryLock(long id);
    @Select("SELECT demand_id FROM cl_exchange_demand WHERE exchange_id=#{id} ORDER BY demand_id")
    List<Long> demandIds(long id);
    @Select("SELECT item_id FROM cl_item_hold WHERE exchange_id=#{id} ORDER BY item_id")
    List<Long> heldItems(long id);
    @Select("SELECT exchange_id FROM cl_item_hold WHERE item_id=#{id} FOR UPDATE")
    Long holdOwner(long id);
    @Select("SELECT COUNT(*) FROM cl_exchange_participant p JOIN cl_exchange e ON e.id=p.exchange_id " +
        "WHERE p.offered_item_id=#{item} AND e.id<>#{exchange} AND e.status IN ('AWAITING_CONFIRMATION','READY','DISPUTED')")
    int otherReferences(@Param("item") long item,@Param("exchange") long exchange);
    @Update("UPDATE cl_exchange_participant SET confirmed_at=#{at} WHERE exchange_id=#{exchange} AND user_id=#{actor} AND confirmed_at IS NULL AND handed_off_at IS NULL AND received_at IS NULL")
    int confirm(@Param("exchange") long exchange,@Param("actor") long actor,@Param("at") LocalDateTime at);
    @Update("UPDATE cl_exchange SET status=#{status},version=version+1,expiry_retry_at=NULL,expiry_failure_code=NULL,cancelled_by=#{cancelledBy},cancellation_reason=#{cancellationReason},cancelled_at=#{cancelledAt} " +
        "WHERE id=#{id} AND version=#{version} AND status IN ('AWAITING_CONFIRMATION','READY')")
    int transition(ExchangeRecord row);
    @Insert("INSERT INTO cl_exchange_event(exchange_id,actor_id,event_type,previous_status,new_status,previous_version,new_version,reason,occurred_at) " +
        "VALUES(#{exchange},#{actor},#{type},#{previous},#{next},#{version},#{version}+1,#{reason},#{at})")
    int event(@Param("exchange") long exchange,@Param("actor") Long actor,@Param("type") String type,
        @Param("previous") String previous,@Param("next") String next,@Param("version") int version,
        @Param("reason") String reason,@Param("at") LocalDateTime at);
    @Delete("DELETE FROM cl_item_hold WHERE item_id=#{item} AND exchange_id=#{exchange}")
    int release(@Param("item") long item,@Param("exchange") long exchange);
    @Update("UPDATE cl_item SET status='AVAILABLE',version=version+1 WHERE id=#{item} AND owner_id=#{owner} AND status='RESERVED' AND version=#{version}")
    int restore(@Param("item") long item,@Param("owner") long owner,@Param("version") int version);
}
