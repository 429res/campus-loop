package edu.campusloop.web.review.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.util.Set;

public record ReviewDecisionRequest(@NotNull @Min(0) Integer version,
                                    @NotNull @Pattern(regexp = "APPROVE|REJECT") String decision,
                                    @NotBlank @Size(max = 1000) String reason) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ReviewDecisionRequest from(JsonNode body) {
        if (body == null || !body.isObject()) throw invalid();
        body.fieldNames().forEachRemaining(field -> {
            if (!Set.of("version", "decision", "reason").contains(field) || body.get(field).isNull()) throw invalid();
        });
        JsonNode version = body.get("version"), decision = body.get("decision"), reason = body.get("reason");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToInt()
            || decision == null || !decision.isTextual() || reason == null || !reason.isTextual()) throw invalid();
        return new ReviewDecisionRequest(version.intValue(), decision.textValue(), reason.textValue().trim());
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("审核请求只接受 version、decision、reason，且须为正确类型");
    }
}
