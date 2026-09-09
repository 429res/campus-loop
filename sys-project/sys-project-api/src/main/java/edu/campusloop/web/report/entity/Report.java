package edu.campusloop.web.report.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("cl_report")
public class Report {
    @TableId(type=IdType.AUTO)
    private Long id;
    private Long reporterId;
    private String reporterDisplayName;
    private String targetType;
    private Long targetId;
    private String targetSummary;
    private String reason;
    private String idempotencyKey;
    private String requestDigest;
    private String status;
    private Integer version;
    private Long acceptedBy;
    private java.time.LocalDateTime acceptedAt;
    private String decision;
    private String decisionReason;
    private Long decidedBy;
    private java.time.LocalDateTime decidedAt;
    private java.time.LocalDateTime createdAt;
}
