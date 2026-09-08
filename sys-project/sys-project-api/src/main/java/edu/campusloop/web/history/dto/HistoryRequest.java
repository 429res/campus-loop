package edu.campusloop.web.history.dto;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
public record HistoryRequest(String eventType,String statement,Instant occurredAt,boolean timeUnknown,
    Long relatedExchangeId,Long correctsEventId,List<String> evidenceUploadIds) {
    @JsonCreator(mode=JsonCreator.Mode.DELEGATING) public static HistoryRequest from(JsonNode body) {
        Set<String> fields=Set.of("eventType","statement","occurredAt","timeUnknown","relatedExchangeId","correctsEventId","evidenceUploadIds");
        if(body==null || !body.isObject() || body.size()!=fields.size()) throw invalid();
        body.fieldNames().forEachRemaining(name->{if(!fields.contains(name)) throw invalid();});
        if(!body.path("eventType").isTextual() || !Set.of("REPAIR","TRANSFER").contains(body.path("eventType").textValue())
            || !body.path("statement").isTextual() || body.path("statement").textValue().trim().isEmpty() || body.path("statement").textValue().trim().length()>2000
            || !body.path("timeUnknown").isBoolean()) throw invalid();
        boolean unknown=body.path("timeUnknown").booleanValue();Instant occurred=null;
        if(unknown) {if(!body.path("occurredAt").isNull()) throw invalid();}
        else {
            if(!body.path("occurredAt").isTextual() || !body.path("occurredAt").textValue().endsWith("Z")) throw invalid();
            try {occurred=Instant.parse(body.path("occurredAt").textValue());} catch(RuntimeException bad){throw invalid();}
            if(occurred.getNano()!=0) throw invalid();
        }
        var evidence=body.path("evidenceUploadIds");if(!evidence.isArray() || evidence.size()>5) throw invalid();
        List<String> ids=new ArrayList<>();
        for(var id:evidence) {
            if(!id.isTextual() || !id.textValue().matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") || ids.contains(id.textValue())) throw invalid();
            ids.add(id.textValue());
        }
        return new HistoryRequest(body.path("eventType").textValue(),body.path("statement").textValue().trim(),occurred,unknown,
            optionalId(body.path("relatedExchangeId")),optionalId(body.path("correctsEventId")),List.copyOf(ids));
    }
    private static Long optionalId(JsonNode id) {
        if(id.isNull()) return null;
        if(!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue()<1) throw invalid();return id.longValue();
    }
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("履历字段、时间或证据引用格式不正确，禁止未知字段");}
}
