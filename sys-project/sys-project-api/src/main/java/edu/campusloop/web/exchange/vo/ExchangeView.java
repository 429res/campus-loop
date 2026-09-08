package edu.campusloop.web.exchange.vo;

import java.time.Instant;
import java.util.List;

/** V2 persisted facts. Names are current public display names; item content/demand snapshots await A-03. */
public record ExchangeView(long id, long initiatorId, String status, int version, Instant createdAt, Instant expiresAt,
                           List<Participant> participants, List<Flow> flows, List<String> allowedActions) {
    public record Participant(long userId, String displayName, long offeredItemId, long receivedItemId,
                              String confirmationStatus, Instant confirmedAt, Instant handedOffAt, Instant receivedAt) {}
    public record Flow(long itemId, long fromUserId, long toUserId) {}
}
