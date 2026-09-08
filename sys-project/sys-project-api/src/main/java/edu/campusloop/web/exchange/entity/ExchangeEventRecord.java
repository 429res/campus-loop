package edu.campusloop.web.exchange.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ExchangeEventRecord {
    private Long id;
    private String eventType;
    private Long actorId;
    private String actorDisplayName;
    private String previousStatus;
    private String newStatus;
    private Integer previousVersion;
    private Integer newVersion;
    private String reason;
    private LocalDateTime occurredAt;
}
