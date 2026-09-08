package edu.campusloop.web.exchange.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import edu.campusloop.exchange.ExchangeCreationCommand;
import java.util.*;

public record CreateExchangeRequest(String ruleVersion, String idempotencyKey, List<ExchangeCreationCommand.ExpectedFlow> flows) {
    public ExchangeCreationCommand command() { return new ExchangeCreationCommand(ruleVersion,idempotencyKey,flows); }
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static CreateExchangeRequest from(JsonNode body) {
        fields(body, Set.of("ruleVersion", "idempotencyKey", "flows"));
        JsonNode flows = body.get("flows");
        if (!flows.isArray() || flows.size() < 2 || flows.size() > 3) throw invalid();
        List<ExchangeCreationCommand.ExpectedFlow> values = new ArrayList<>();
        for (JsonNode flow : flows) {
            fields(flow, Set.of("itemId", "itemVersion", "demandId", "demandVersion"));
            values.add(new ExchangeCreationCommand.ExpectedFlow(number(flow,"itemId",false),
                (int)number(flow,"itemVersion",true), number(flow,"demandId",false), (int)number(flow,"demandVersion",true)));
        }
        return new CreateExchangeRequest(string(body,"ruleVersion"),string(body,"idempotencyKey"),List.copyOf(values));
    }
    private static void fields(JsonNode value, Set<String> allowed) {
        if (value == null || !value.isObject() || value.size() != allowed.size()) throw invalid();
        value.fieldNames().forEachRemaining(f -> { if (!allowed.contains(f) || value.get(f).isNull()) throw invalid(); });
    }
    private static String string(JsonNode value,String key) {
        if (!value.get(key).isTextual()) throw invalid();
        return value.get(key).textValue();
    }
    private static long number(JsonNode value,String key,boolean integer) {
        JsonNode n=value.get(key);
        if (!n.isIntegralNumber() || !n.canConvertToLong() || (integer && !n.canConvertToInt())) throw invalid();
        return n.longValue();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("创建请求字段或类型不正确"); }
}
