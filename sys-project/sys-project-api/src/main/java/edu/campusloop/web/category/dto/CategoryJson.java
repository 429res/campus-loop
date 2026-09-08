package edu.campusloop.web.category.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Local strict parsing preserves PATCH omission and rejects explicit null/coercion. */
final class CategoryJson {
    private CategoryJson() {}

    static void fields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject()) throw invalid();
        body.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name) || body.get(name).isNull()) throw invalid();
        });
    }

    static String string(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null) return null;
        if (!value.isTextual()) throw invalid();
        return value.textValue().trim();
    }

    static Integer integer(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
        return value.intValue();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("分类请求包含未知字段、空值或不正确的类型");
    }
}
