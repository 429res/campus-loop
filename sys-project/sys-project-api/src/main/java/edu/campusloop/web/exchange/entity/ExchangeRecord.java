package edu.campusloop.web.exchange.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("cl_exchange")
public class ExchangeRecord {
    @TableId(type=IdType.AUTO) private Long id;
    private Long initiatorId;
    private String status;
    private Integer version;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private String ruleVersion;
    private String requestDigest;
    private String creationSnapshot;
    private Long cancelledBy;
    private String cancellationReason;
    private LocalDateTime cancelledAt;
}
