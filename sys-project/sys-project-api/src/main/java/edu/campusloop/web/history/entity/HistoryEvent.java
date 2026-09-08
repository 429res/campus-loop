package edu.campusloop.web.history.entity;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class HistoryEvent {
    private Long id,itemId,exchangeId,sourceUserId,counterpartyUserId,verifiedByUserId,correctsEventId,correctedByEventId;
    private String eventType,description,evidenceLevel,authorDisplayName;
    private LocalDateTime occurredAt,recordedAt,confirmedAt,verifiedAt,ownershipStartedAt,ownershipEndedAt;
}
