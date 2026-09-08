package edu.campusloop.web.history.vo;
import java.time.Instant;
import java.util.List;
public record HistoryView(long id,long itemId,String eventType,String statement,String evidenceLevel,String authorDisplayName,
    Instant occurredAt,boolean timeUnknown,Instant recordedAt,Long correctsEventId,Long correctedByEventId,Instant confirmedAt,Instant verifiedAt,
    Long authorId,Long relatedExchangeId,List<Evidence> evidence,boolean canCorrect) {
    public record Evidence(String uploadId,String url,String mediaType) {}
}
