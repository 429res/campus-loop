package edu.campusloop.web.auth.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ChangePasswordRequest {
    @NotBlank
    @Size(max = 72)
    private String currentPassword;

    @NotBlank
    @Size(min = 12, max = 64)
    private String newPassword;

    public String currentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String newPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    @JsonAnySetter
    public void rejectUnsupportedField(String field, Object ignored) {
        throw new IllegalArgumentException("不允许修改字段: " + field);
    }
}
