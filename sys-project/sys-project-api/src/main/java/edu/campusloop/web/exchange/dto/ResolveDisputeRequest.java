package edu.campusloop.web.exchange.dto;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import edu.campusloop.common.ApiException;
public record ResolveDisputeRequest(@NotNull @Min(0) Integer version,@NotBlank @Pattern(regexp="RESUME|CANCEL") String decision,
 @NotBlank @Size(max=1000) String reason,@NotNull Boolean returnConfirmed) {
 @JsonAnySetter public void reject(String key,Object value){throw new ApiException(400,"不支持的处理字段");}
}
