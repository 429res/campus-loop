package edu.campusloop.web.auth.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class UpdateProfileRequest {
    @NotBlank
    @Size(max = 64)
    private String displayName;

    public String displayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    @JsonAnySetter
    public void rejectUnsupportedField(String field, Object ignored) {
        throw new IllegalArgumentException("不允许修改字段: " + field);
    }
}
