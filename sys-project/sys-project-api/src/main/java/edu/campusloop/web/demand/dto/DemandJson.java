package edu.campusloop.web.demand.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Strict parsing stays local to this API, including PATCH absent/null semantics. */
final class DemandJson {
    private DemandJson() {}

    static void fields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject()) throw invalid();
        body.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name) || body.get(name).isNull()) throw invalid();
        });
    }

    static Long number(JsonNode body, String name, boolean required) {
        JsonNode value = value(body, name, required);
        if (value == null) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        return value.longValue();
    }

    static Integer version(JsonNode body) {
        Long value = number(body, "version", true);
        if (value < 0 || value > Integer.MAX_VALUE) throw invalid();
        return value.intValue();
    }

    static String string(JsonNode body, String name, boolean required) {
        JsonNode value = value(body, name, required);
        if (value == null) return null;
        if (!value.isTextual()) throw invalid();
        return value.textValue();
    }

    static List<String> tags(JsonNode body, boolean required) {
        JsonNode values = value(body, "preferredTags", required);
        if (values == null) return null;
        if (!values.isArray()) throw invalid();
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) throw invalid();
            result.add(value.textValue());
        }
        return result;
    }

    static List<Long> items(JsonNode body, boolean required) {
        JsonNode values = value(body, "offeredItemIds", required);
        if (values == null) return null;
        if (!values.isArray()) throw invalid();
        List<Long> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
            result.add(value.longValue());
        }
        return result;
    }

    private static JsonNode value(JsonNode body, String name, boolean required) {
        JsonNode value = body.get(name);
        if ((required && value == null) || (value != null && value.isNull())) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("需求请求包含未知字段、空值或不正确的类型");
    }
}
