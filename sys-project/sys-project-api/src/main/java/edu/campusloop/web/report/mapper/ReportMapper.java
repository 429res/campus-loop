package edu.campusloop.web.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.campusloop.web.report.entity.Report;
import org.apache.ibatis.annotations.*;

public interface ReportMapper extends BaseMapper<Report> {
    @Select("SELECT * FROM cl_report WHERE id=#{id} FOR UPDATE")
    Report selectForUpdate(@Param("id") long id);

    @Select("SELECT * FROM cl_report WHERE reporter_id=#{reporterId} AND idempotency_key=#{key}")
    Report selectReplay(@Param("reporterId") long reporterId,@Param("key") String key);

    @Select("SELECT * FROM cl_report WHERE reporter_id=#{reporterId} AND target_type=#{targetType} AND target_id=#{targetId} " +
        "AND status IN ('SUBMITTED','IN_REVIEW') ORDER BY id DESC LIMIT 1")
    Report selectOpen(@Param("reporterId") long reporterId,@Param("targetType") String targetType,@Param("targetId") long targetId);

    @Update("UPDATE cl_report SET status='IN_REVIEW',version=version+1,accepted_by=#{actorId},accepted_at=#{at} " +
        "WHERE id=#{id} AND status='SUBMITTED' AND version=#{version}")
    int accept(@Param("id") long id,@Param("version") int version,@Param("actorId") long actorId,
               @Param("at") java.time.LocalDateTime at);

    @Update("UPDATE cl_report SET status='RESOLVED',version=version+1,decision=#{decision},decision_reason=#{reason}," +
        "decided_by=#{actorId},decided_at=#{at} WHERE id=#{id} AND status='IN_REVIEW' AND version=#{version}")
    int decide(@Param("id") long id,@Param("version") int version,@Param("decision") String decision,
               @Param("reason") String reason,@Param("actorId") long actorId,@Param("at") java.time.LocalDateTime at);
}
