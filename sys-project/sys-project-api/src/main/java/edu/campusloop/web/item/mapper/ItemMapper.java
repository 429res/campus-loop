package edu.campusloop.web.item.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.campusloop.web.item.entity.Item;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ItemMapper extends BaseMapper<Item> {
    @Select("SELECT * FROM cl_item WHERE id = #{id} FOR UPDATE")
    Item selectForUpdate(@Param("id") long id);

    @Select("SELECT item_id FROM cl_item_hold WHERE item_id = #{id} FOR UPDATE")
    Long lockHold(@Param("id") long id);

    // Read after the item lock. Exchange writers must lock affected items before changing references.
    @Select("SELECT COUNT(*) FROM cl_exchange_participant p JOIN cl_exchange e ON e.id = p.exchange_id " +
        "WHERE p.offered_item_id = #{id} AND e.status IN ('AWAITING_CONFIRMATION','READY','DISPUTED')")
    long activeExchangeReferences(@Param("id") long id);
}
