package edu.campusloop.web.category.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.util.Set;

public record CreateCategoryRequest(@NotBlank @Size(max = 64) String name,
                                    @Min(0) @Max(9999) Integer sortOrder) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static CreateCategoryRequest from(JsonNode body) {
        CategoryJson.fields(body, Set.of("name", "sortOrder"));
        return new CreateCategoryRequest(CategoryJson.string(body, "name"), CategoryJson.integer(body, "sortOrder"));
    }
}
