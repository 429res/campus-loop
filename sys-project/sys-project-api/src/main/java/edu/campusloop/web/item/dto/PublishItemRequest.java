package edu.campusloop.web.item.dto;
import jakarta.validation.constraints.*;
import java.util.List;
public record PublishItemRequest(
    @NotBlank @Size(max=100) String title,
    @NotBlank @Size(max=2000) String description,
    @NotNull @Positive Long categoryId,
    @NotNull @Min(1) @Max(5) Integer conditionLevel,
    @NotNull @Size(max=8) List<@NotBlank @Size(max=20) String> tags,
    @NotNull @Positive Long wantedCategoryId,
    @NotNull @Size(max=8) List<@NotBlank @Size(max=20) String> wantedTags,
    @Size(max=255) String imageUrl) {}
