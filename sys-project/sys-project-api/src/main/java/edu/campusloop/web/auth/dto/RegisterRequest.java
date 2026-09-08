package edu.campusloop.web.auth.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class RegisterRequest {
    @NotBlank
    @Size(min = 3, max = 64)
    @Pattern(regexp = "[A-Za-z0-9_.-]+")
    private String username;

    @NotBlank
    @Size(min = 12, max = 64)
    private String password;

    @NotBlank
    @Size(max = 64)
    private String displayName;

    public String username() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username == null ? null : username.trim();
    }

    public String password() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String displayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName == null ? null : displayName.trim();
    }

    @JsonAnySetter
    public void rejectUnsupportedField(String field, Object ignored) {
        throw new IllegalArgumentException("注册不接受字段: " + field);
    }
}
