package edu.campusloop.web.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.campusloop.web.report.entity.ReportAudit;
import org.apache.ibatis.annotations.*;

public interface ReportAuditMapper extends BaseMapper<ReportAudit> {
    @Select("SELECT * FROM cl_report_audit WHERE report_id=#{reportId} AND action=#{action}")
    ReportAudit selectAction(@Param("reportId") long reportId,@Param("action") String action);
}
