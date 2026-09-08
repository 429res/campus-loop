package edu.campusloop.web.exchange.mapper;

import edu.campusloop.web.exchange.entity.ExchangeCandidateItem;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface ExchangeCandidateMapper {
    @Select("<script>SELECT i.*,u.display_name AS owner_name,u.status AS user_status,c.name AS category_name," +
        "CASE WHEN EXISTS(SELECT 1 FROM cl_item_hold h WHERE h.item_id=i.id) OR " +
        "EXISTS(SELECT 1 FROM cl_exchange_participant p JOIN cl_exchange e ON e.id=p.exchange_id " +
        "WHERE p.offered_item_id=i.id AND e.status IN ('AWAITING_CONFIRMATION','READY','DISPUTED')) THEN 1 ELSE 0 END AS blocked " +
        "FROM cl_item i JOIN cl_user u ON u.id=i.owner_id JOIN cl_category c ON c.id=i.category_id " +
        "WHERE i.id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY i.id</script>")
    List<ExchangeCandidateItem> items(@Param("ids") List<Long> ids);
}
