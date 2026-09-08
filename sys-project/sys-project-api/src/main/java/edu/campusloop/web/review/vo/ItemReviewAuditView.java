package edu.campusloop.web.review.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;

public record ItemReviewAuditView(long id, long itemId, long operatorUserId, String operatorDisplayName,
                                  String action, String reason, String previousStatus, String newStatus,
                                  Integer previousVersion, int newVersion, JsonNode previousSnapshot, JsonNode newSnapshot,
                                  @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss'Z'") LocalDateTime createdAt) {}
