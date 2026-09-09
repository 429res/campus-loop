package edu.campusloop.web.report.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("cl_report_audit")
public class ReportAudit {
    @TableId(type=IdType.AUTO)
    private Long id;
    private Long reportId;
    private Long actorUserId;
    private String actorDisplayName;
    private String action;
    private String previousStatus;
    private String newStatus;
    private Integer previousVersion;
    private Integer newVersion;
    private String reason;
    private String decision;
    private String requestDigest;
    private java.time.LocalDateTime createdAt;
}
