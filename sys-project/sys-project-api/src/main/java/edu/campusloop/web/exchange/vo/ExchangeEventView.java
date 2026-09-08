package edu.campusloop.web.exchange.vo;
import java.time.Instant;
/** Minimal administrator trace; handover notes and private snapshots are deliberately absent. */
public record ExchangeEventView(long id, long exchangeId, Long actorId, String eventType,
    String previousStatus, String newStatus, int previousVersion, int newVersion,
    String reason, Instant occurredAt) {}
