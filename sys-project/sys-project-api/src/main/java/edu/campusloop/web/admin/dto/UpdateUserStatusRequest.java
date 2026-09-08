package edu.campusloop.web.admin.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

public class UpdateUserStatusRequest {
    @NotBlank
    @Pattern(regexp = "ACTIVE|DISABLED")
    private String status;

    @NotNull
    @Min(0)
    private Integer version;

    @NotBlank
    @Size(max = 500)
    private String reason;

    public String status() { return status; }
    public Integer version() { return version; }
    public String reason() { return reason; }
    public void setStatus(String status) { this.status=status; }
    public void setVersion(Integer version) { this.version=version; }
    public void setReason(String reason) { this.reason=reason; }

    @JsonAnySetter
    public void rejectUnsupportedField(String field,Object ignored) {
        throw new IllegalArgumentException("不允许修改字段: " + field);
    }
}
