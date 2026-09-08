package edu.campusloop.web.exchange.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Strict action DTOs: identity/status/time never come from a client. */
public final class ExchangeActionRequest {
    private ExchangeActionRequest() {}
    public record Confirm(int version) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING) public static Confirm from(JsonNode body) {
            fields(body,Set.of("version"));return new Confirm(ExchangeActionRequest.version(body));
        }
    }
    public record Cancel(int version,String reason) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING) public static Cancel from(JsonNode body) {
            fields(body,Set.of("version","reason"));
            var reason=body.get("reason");
            if(!reason.isTextual() || reason.textValue().trim().isEmpty() || reason.textValue().trim().length()>1000) throw invalid();
            return new Cancel(ExchangeActionRequest.version(body),reason.textValue().trim());
        }
    }
    private static void fields(JsonNode body,Set<String> allowed) {
        if(body==null || !body.isObject() || body.size()!=allowed.size()) throw invalid();
        body.fieldNames().forEachRemaining(name -> {if(!allowed.contains(name)) throw invalid();});
    }
    private static int version(JsonNode body) {
        var value=body.get("version");
        if(value==null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue()<0) throw invalid();
        return value.intValue();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("需要非负整数version；取消原因须为1–1000字，禁止未知字段"); }
}
