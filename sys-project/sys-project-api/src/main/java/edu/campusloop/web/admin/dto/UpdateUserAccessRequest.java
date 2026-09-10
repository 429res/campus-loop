package edu.campusloop.web.admin.dto;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import edu.campusloop.common.ApiException;
import java.util.List;
public record UpdateUserAccessRequest(@NotNull @Min(0) Integer version,@Pattern(regexp="ADMIN|USER") @NotBlank String role,
 @NotNull @Size(max=8) List<@NotBlank String> permissions,@NotBlank @Size(max=500) String reason){
 @JsonAnySetter public void reject(String key,Object value){throw new ApiException(400,"不支持的权限字段");}
}
