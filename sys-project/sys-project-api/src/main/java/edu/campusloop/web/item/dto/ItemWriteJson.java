package edu.campusloop.web.item.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Strict types and a local whitelist; field constraints remain on PublishItemRequest. */
final class ItemWriteJson {
    private ItemWriteJson() {}

    static void fields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject()) throw invalid();
        body.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw invalid();
        });
    }

    static Long number(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        return value.longValue();
    }

    static Integer integer(JsonNode body, String name) {
        long value = number(body, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw invalid();
        return (int) value;
    }

    static String string(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if ("imageUrl".equals(name) && (value == null || value.isNull())) return null;
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }

    static List<String> tags(JsonNode body, String name) {
        JsonNode values = body.get(name);
        if (values == null || !values.isArray()) throw invalid();
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) throw invalid();
            result.add(value.textValue());
        }
        return result;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("物品请求包含未知字段、缺少必填字段或类型不正确");
    }
}
