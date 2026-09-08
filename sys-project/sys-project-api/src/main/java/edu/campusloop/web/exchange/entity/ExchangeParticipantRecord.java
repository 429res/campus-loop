package edu.campusloop.web.exchange.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** Only persisted routing and timestamps; never join current private item content into historical exchanges. */
@Data
public class ExchangeParticipantRecord {
    private Long exchangeId;
    private Long userId;
    private String displayName;
    private Long offeredItemId;
    private Long recipientUserId;
    private LocalDateTime confirmedAt;
    private LocalDateTime handedOffAt;
    private LocalDateTime receivedAt;
}
