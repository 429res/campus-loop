package edu.campusloop.web.demand.mapper;

import edu.campusloop.web.demand.entity.DemandItem;
import edu.campusloop.web.item.entity.Item;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface DemandItemMapper {
    @Select("SELECT item_id FROM cl_demand_item WHERE demand_id = #{demandId} ORDER BY item_id")
    List<Long> itemIds(@Param("demandId") long demandId);

    @Select("<script>SELECT demand_id, item_id FROM cl_demand_item WHERE demand_id IN " +
        "<foreach collection='demandIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
        "ORDER BY demand_id, item_id</script>")
    List<DemandItem> associations(@Param("demandIds") List<Long> demandIds);

    @Insert("INSERT INTO cl_demand_item(demand_id, item_id) VALUES(#{demandId}, #{itemId})")
    int insert(@Param("demandId") long demandId, @Param("itemId") long itemId);

    @Delete("DELETE FROM cl_demand_item WHERE demand_id = #{demandId}")
    int deleteForDemand(@Param("demandId") long demandId);

    @Select("SELECT * FROM cl_item WHERE id = #{itemId} FOR UPDATE")
    Item lockItem(@Param("itemId") long itemId);

    @Select("SELECT item_id FROM cl_item_hold WHERE item_id = #{itemId} FOR UPDATE")
    Long lockHold(@Param("itemId") long itemId);

    @Select("<script>SELECT item_id FROM cl_item_hold WHERE item_id IN " +
        "<foreach collection='itemIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<Long> heldItemIds(@Param("itemIds") List<Long> itemIds);
}
