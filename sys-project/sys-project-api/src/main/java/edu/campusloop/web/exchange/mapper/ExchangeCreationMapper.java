package edu.campusloop.web.exchange.mapper;

import edu.campusloop.web.exchange.entity.ExchangeCreationRecord;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

public interface ExchangeCreationMapper {
    @Select("SELECT id,request_digest FROM cl_exchange WHERE initiator_id=#{user} AND idempotency_key=#{key}")
    ExchangeCreationRecord replay(@Param("user") long user, @Param("key") String key);

    @Select("<script>SELECT DISTINCT d.id FROM cl_demand d JOIN cl_demand_item di ON di.demand_id=d.id " +
        "JOIN cl_item i ON i.id=di.item_id AND i.owner_id=d.owner_id " +
        "WHERE d.status='ACTIVE' AND di.item_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
        "ORDER BY d.id LIMIT 20001</script>")
    List<Long> associatedDemandIds(@Param("ids") List<Long> ids);

    @Insert("INSERT INTO cl_exchange(initiator_id,status,version,idempotency_key,request_digest,rule_version,creation_snapshot,created_at,expires_at) " +
        "VALUES(#{initiatorId},#{status},0,#{idempotencyKey},#{requestDigest},#{ruleVersion},#{creationSnapshot},#{createdAt},#{expiresAt})")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insert(ExchangeCreationRecord row);

    @Insert("INSERT INTO cl_exchange_participant(exchange_id,user_id,offered_item_id,recipient_user_id) VALUES(#{exchange},#{user},#{item},#{recipient})")
    int participant(@Param("exchange") long exchange,@Param("user") long user,@Param("item") long item,@Param("recipient") long recipient);

    @Insert("INSERT INTO cl_exchange_demand(exchange_id,demand_id,offered_item_id,demand_version,demand_snapshot) VALUES(#{exchange},#{demand},#{offer},#{version},#{snapshot})")
    int demand(@Param("exchange") long exchange,@Param("demand") long demand,@Param("offer") long offer,
               @Param("version") int version,@Param("snapshot") String snapshot);

    @Insert("INSERT INTO cl_item_hold(item_id,exchange_id,expires_at) VALUES(#{item},#{exchange},#{expires})")
    int hold(@Param("item") long item,@Param("exchange") long exchange,@Param("expires") LocalDateTime expires);

    @Update("UPDATE cl_item SET status='RESERVED',version=version+1 " +
        "WHERE id=#{item} AND owner_id=#{owner} AND status='AVAILABLE' AND version=#{version}")
    int reserve(@Param("item") long item,@Param("owner") long owner,@Param("version") int version);
}
