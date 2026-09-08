package edu.campusloop.web.demand.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.campusloop.web.demand.entity.Demand;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface DemandMapper extends BaseMapper<Demand> {
    @Select("SELECT COUNT(*) FROM cl_exchange_demand d JOIN cl_exchange e ON e.id=d.exchange_id " +
        "WHERE d.demand_id=#{id} AND e.status IN ('AWAITING_CONFIRMATION','READY','DISPUTED')")
    long activeExchangeReferences(@Param("id") long id);

    @Select("SELECT * FROM cl_demand WHERE id = #{id} FOR UPDATE")
    Demand selectForUpdate(@Param("id") long id);
}
