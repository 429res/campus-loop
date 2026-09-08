package edu.campusloop.web.demand.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.campusloop.web.demand.entity.Demand;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface DemandMapper extends BaseMapper<Demand> {
    @Select("SELECT * FROM cl_demand WHERE id = #{id} FOR UPDATE")
    Demand selectForUpdate(@Param("id") long id);
}
