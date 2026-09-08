package edu.campusloop.web.item.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Set;

/** PUT accepts the existing publish form plus version, flattened in JSON. */
public record EditItemRequest(@NotNull @Min(0) Integer version,
                              @NotNull @Valid PublishItemRequest fields) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static EditItemRequest from(JsonNode body) {
        ItemWriteJson.fields(body, Set.of("version", "title", "description", "categoryId", "conditionLevel",
            "tags", "wantedCategoryId", "wantedTags", "imageUrl"));
        return new EditItemRequest(ItemWriteJson.integer(body, "version"), new PublishItemRequest(
            ItemWriteJson.string(body, "title"), ItemWriteJson.string(body, "description"),
            ItemWriteJson.number(body, "categoryId"), ItemWriteJson.integer(body, "conditionLevel"),
            ItemWriteJson.tags(body, "tags"), ItemWriteJson.number(body, "wantedCategoryId"),
            ItemWriteJson.tags(body, "wantedTags"), ItemWriteJson.string(body, "imageUrl")));
    }
}
