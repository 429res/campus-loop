package edu.campusloop.web.demand.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.util.Set;

public record DemandStatusRequest(
    @NotNull @Min(0) Integer version,
    @NotNull @Pattern(regexp = "ACTIVE|INACTIVE") String status) {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static DemandStatusRequest from(JsonNode body) {
        DemandJson.fields(body, Set.of("version", "status"));
        return new DemandStatusRequest(DemandJson.version(body), DemandJson.string(body, "status", true));
    }
}
