package edu.campusloop.web.review.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("cl_item_review_audit")
public class ItemReviewAudit {
    @TableId(type = IdType.AUTO) private Long id;
    private Long itemId;
    private Long operatorUserId;
    private String operatorDisplayName;
    private String action;
    private String reason;
    private String previousStatus;
    private String newStatus;
    private Integer previousVersion;
    private Integer newVersion;
    private String previousSnapshotJson;
    private String newSnapshotJson;
    private LocalDateTime createdAt;
}
