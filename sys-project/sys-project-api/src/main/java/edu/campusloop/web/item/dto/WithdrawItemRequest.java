package edu.campusloop.web.item.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.util.Set;

public record WithdrawItemRequest(@NotNull @Min(0) Integer version) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static WithdrawItemRequest from(JsonNode body) {
        ItemWriteJson.fields(body, Set.of("version"));
        return new WithdrawItemRequest(ItemWriteJson.integer(body, "version"));
    }
}
