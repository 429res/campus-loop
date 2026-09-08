package edu.campusloop.web.exchange.vo;

import java.time.Instant;
import java.util.List;

/** Explicit administration projection; never serialize persistence metadata or the original JSON snapshot. */
public record AdminExchangeDetail(ExchangeView exchange, List<Event> events, Creation creation) {
    public record Event(long id, String eventType, Long actorId, String actorDisplayName,
                        String previousStatus, String newStatus, int previousVersion, int newVersion,
                        String reason, Instant occurredAt) {}
    public record Creation(String ruleVersion, List<CreationFlow> flows) {}
    public record CreationFlow(long itemId, String itemTitle, long fromUserId, long toUserId,
                               long demandId, String matchedCategoryName, String reason) {}
}
