package edu.campusloop.web.admin.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("cl_user_status_audit")
public class UserStatusAudit {
    @TableId(type=IdType.AUTO)
    private Long id;
    private Long targetUserId;
    private Long operatorUserId;
    private String previousStatus;
    private String newStatus;
    private String reason;
    private Integer previousVersion;
    private Integer newVersion;
    private java.time.LocalDateTime createdAt;
}
