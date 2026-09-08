package edu.campusloop.web.exchange.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** Persistence-only metadata; never returned in participant responses. */
@Data
public class ExchangeCreationRecord {
    private Long id;
    private long initiatorId;
    private String idempotencyKey;
    private String requestDigest;
    private String ruleVersion;
    private String creationSnapshot;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
