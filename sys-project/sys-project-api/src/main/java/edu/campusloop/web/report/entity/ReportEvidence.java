package edu.campusloop.web.report.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("cl_report_evidence")
public class ReportEvidence {
    @TableId(type=IdType.AUTO)
    private Long id;
    private Long reportId;
    private String uploadId;
    private Integer positionNo;
    private String displayName;
    private String contentType;
    private Long sizeBytes;
    private String contentHash;
}
