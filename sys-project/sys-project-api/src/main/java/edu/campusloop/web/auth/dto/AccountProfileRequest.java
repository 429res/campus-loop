package edu.campusloop.web.auth.dto;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import edu.campusloop.common.ApiException;
public record AccountProfileRequest(@NotNull @Min(0) Integer version,@NotBlank @Size(max=64) String displayName,
    @Size(max=255) String avatarUrl,@Size(max=300) String bio,@Size(max=100) String campus,@Size(max=160) String contact) {
    @JsonAnySetter public void reject(String key,Object value){throw new ApiException(400,"不支持的资料字段");}
}
