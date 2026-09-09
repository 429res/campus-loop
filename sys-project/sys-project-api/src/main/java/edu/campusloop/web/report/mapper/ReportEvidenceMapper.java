package edu.campusloop.web.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.campusloop.web.report.entity.ReportEvidence;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface ReportEvidenceMapper extends BaseMapper<ReportEvidence> {
    @Select("SELECT * FROM cl_report_evidence WHERE report_id=#{reportId} ORDER BY position_no,id")
    List<ReportEvidence> selectForReport(@Param("reportId") long reportId);

    @Select("SELECT * FROM cl_report_evidence WHERE report_id=#{reportId} AND id=#{evidenceId}")
    ReportEvidence selectForReportAndId(@Param("reportId") long reportId,@Param("evidenceId") long evidenceId);
}
