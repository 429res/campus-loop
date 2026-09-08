package edu.campusloop.web.demand.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Set;

public record CreateDemandRequest(
    @NotNull @Positive Long categoryId,
    @NotNull @Size(max = 2000) String description,
    @NotNull @Size(max = 8) List<@NotBlank @Size(max = 20) String> preferredTags,
    @NotNull @Size(max = 100) List<@NotNull @Positive Long> offeredItemIds) {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static CreateDemandRequest from(JsonNode body) {
        DemandJson.fields(body, Set.of("categoryId", "description", "preferredTags", "offeredItemIds"));
        return new CreateDemandRequest(DemandJson.number(body, "categoryId", true),
            DemandJson.string(body, "description", true), DemandJson.tags(body, true), DemandJson.items(body, true));
    }
}
