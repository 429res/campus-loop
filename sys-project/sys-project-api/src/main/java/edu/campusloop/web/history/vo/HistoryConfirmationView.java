package edu.campusloop.web.history.vo;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

public record HistoryConfirmationView(String mode,String status,int confirmedCount,int requiredCount,boolean currentContent,
    Instant requestedAt,Instant completedAt,Instant withdrawnAt,String snapshotHash,JsonNode snapshot,
    List<Participant> participants,String withdrawalReason,List<String> allowedActions) {
    public record Participant(long userId,String displayName,Instant confirmedAt,Long offeredItemId,Long receivedItemId,Instant handedOffAt,Instant receivedAt) {}
}
