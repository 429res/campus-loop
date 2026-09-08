package edu.campusloop.web.matching.mapper;

import edu.campusloop.web.matching.entity.MatchingDemandAssociation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

public interface IndependentMatchingMapper {
    @Select("<script>" +
        "SELECT d.id AS demand_id, d.owner_id, d.category_id, d.preferred_tags_json, " +
        "d.status, d.version, di.item_id " +
        "FROM cl_demand d JOIN cl_demand_item di ON di.demand_id = d.id " +
        "JOIN cl_item i ON i.id = di.item_id AND i.owner_id = d.owner_id " +
        "WHERE d.status = 'ACTIVE' AND NOT EXISTS (" +
        "SELECT 1 FROM cl_exchange_demand ed JOIN cl_exchange e ON e.id = ed.exchange_id " +
        "WHERE ed.demand_id = d.id AND e.status IN ('AWAITING_CONFIRMATION','READY','DISPUTED')) " +
        "AND di.item_id IN " +
        "<foreach collection='itemIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
        "ORDER BY d.id, di.item_id LIMIT #{rowLimit}" +
        "</script>")
    List<MatchingDemandAssociation> activeAssociations(@Param("itemIds") List<Long> itemIds,
                                                     @Param("rowLimit") int rowLimit);
}
