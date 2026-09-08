package edu.campusloop.web.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.campusloop.web.review.entity.ItemReviewAudit;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface ItemReviewAuditMapper extends BaseMapper<ItemReviewAudit> {
    @Select("<script>SELECT a.* FROM cl_item_review_audit a JOIN (" +
        "SELECT item_id, MAX(new_version) AS latest FROM cl_item_review_audit " +
        "WHERE action IN ('APPROVE','REJECT') AND item_id IN " +
        "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
        "GROUP BY item_id) latest ON latest.item_id=a.item_id AND latest.latest=a.new_version</script>")
    List<ItemReviewAudit> latestDecisions(@Param("ids") List<Long> ids);
}
