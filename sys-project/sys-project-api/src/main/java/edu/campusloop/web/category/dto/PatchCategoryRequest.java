package edu.campusloop.web.category.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.util.Set;

public record PatchCategoryRequest(@NotNull @Min(0) Integer version,
                                   @Size(min = 1, max = 64) String name,
                                   @Min(0) @Max(9999) Integer sortOrder,
                                   @Pattern(regexp = "ACTIVE|INACTIVE") String status) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static PatchCategoryRequest from(JsonNode body) {
        CategoryJson.fields(body, Set.of("version", "name", "sortOrder", "status"));
        return new PatchCategoryRequest(CategoryJson.integer(body, "version"), CategoryJson.string(body, "name"),
            CategoryJson.integer(body, "sortOrder"), CategoryJson.string(body, "status"));
    }

    @AssertTrue(message = "至少提交一个可编辑字段，名称不能为空白")
    public boolean isUpdateValid() {
        return (name != null || sortOrder != null || status != null) && (name == null || !name.isBlank());
    }
}
