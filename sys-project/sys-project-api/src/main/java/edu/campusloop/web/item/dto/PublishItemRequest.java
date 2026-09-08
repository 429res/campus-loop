package edu.campusloop.web.item.dto;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
public record PublishItemRequest(
    @NotBlank @Size(max=100) String title,
    @NotBlank @Size(max=2000) String description,
    @NotNull @Positive Long categoryId,
    @NotNull @Min(1) @Max(5) Integer conditionLevel,
    @NotNull @Size(max=8) List<@NotBlank @Size(max=20) String> tags,
    @NotNull @Positive Long wantedCategoryId,
    @NotNull @Size(max=8) List<@NotBlank @Size(max=20) String> wantedTags,
    @Size(max=255) String imageUrl) {
    @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
    public static PublishItemRequest from(JsonNode body) {
        ItemWriteJson.fields(body,Set.of("title","description","categoryId","conditionLevel","tags","wantedCategoryId","wantedTags","imageUrl"));
        return new PublishItemRequest(ItemWriteJson.string(body,"title"),ItemWriteJson.string(body,"description"),
            ItemWriteJson.number(body,"categoryId"),ItemWriteJson.integer(body,"conditionLevel"),ItemWriteJson.tags(body,"tags"),
            ItemWriteJson.number(body,"wantedCategoryId"),ItemWriteJson.tags(body,"wantedTags"),ItemWriteJson.string(body,"imageUrl"));
    }
}
