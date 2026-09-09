package edu.campusloop.web.exchange.vo;

import java.time.Instant;
import java.util.List;

/** Participant-visible persisted facts; internal creation and private demand snapshots are never returned. */
public record ExchangeView(long id, long initiatorId, String status, int version, Instant createdAt, Instant expiresAt,
                           List<Participant> participants, List<Flow> flows, List<String> allowedActions,
                           Long cancelledBy, String cancellationReason, Instant cancelledAt, Long disputedBy, String disputeReason, Instant disputedAt,String ruleVersion) {
    public record Participant(long userId, String displayName, long offeredItemId, long receivedItemId,
                              String confirmationStatus, Instant confirmedAt, Instant handedOffAt, Instant receivedAt, String handedOffNote, String receivedNote) {}
    public record Flow(long itemId, long fromUserId, long toUserId,String itemTitle,String imageUrl) {}
}
