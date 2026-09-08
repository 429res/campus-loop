package edu.campusloop.web.demand.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Set;

public record PatchDemandRequest(
    @NotNull @Min(0) Integer version,
    @Positive Long categoryId,
    @Size(max = 2000) String description,
    @Size(max = 8) List<@NotBlank @Size(max = 20) String> preferredTags,
    @Size(max = 100) List<@NotNull @Positive Long> offeredItemIds) {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static PatchDemandRequest from(JsonNode body) {
        DemandJson.fields(body, Set.of("version", "categoryId", "description", "preferredTags", "offeredItemIds"));
        return new PatchDemandRequest(DemandJson.version(body), DemandJson.number(body, "categoryId", false),
            DemandJson.string(body, "description", false), DemandJson.tags(body, false), DemandJson.items(body, false));
    }

    @AssertTrue(message = "至少提交一个可编辑字段")
    public boolean isUpdatePresent() {
        return categoryId != null || description != null || preferredTags != null || offeredItemIds != null;
    }
}
