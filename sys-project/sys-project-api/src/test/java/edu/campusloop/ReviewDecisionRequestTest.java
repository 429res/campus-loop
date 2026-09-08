package edu.campusloop;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campusloop.web.review.dto.ReviewDecisionRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReviewDecisionRequestTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void acceptsOnlyDeclaredFieldsAndStrictTypes() throws Exception {
        var request = json.readValue("{\"version\":3,\"decision\":\"REJECT\",\"reason\":\"  说明已知瑕疵  \"}", ReviewDecisionRequest.class);
        assertEquals(3, request.version()); assertEquals("说明已知瑕疵", request.reason());
        for (String body : List.of("{}", "[]", "{\"version\":\"3\",\"decision\":\"APPROVE\",\"reason\":\"理由\"}",
            "{\"version\":3.2,\"decision\":\"APPROVE\",\"reason\":\"理由\"}",
            "{\"version\":2147483648,\"decision\":\"APPROVE\",\"reason\":\"理由\"}",
            "{\"version\":3,\"decision\":\"APPROVE\",\"reason\":null}",
            "{\"version\":3,\"decision\":\"APPROVE\",\"reason\":\"理由\",\"reviewedById\":1}"))
            assertThrows(Exception.class, () -> json.readValue(body, ReviewDecisionRequest.class));
    }

    @Test void validatesDecisionVersionAndBoundedNonblankReason() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(new ReviewDecisionRequest(0,"APPROVE","理由")).isEmpty());
            for (var request : List.of(new ReviewDecisionRequest(-1,"APPROVE","理由"),
                new ReviewDecisionRequest(0,"AVAILABLE","理由"), new ReviewDecisionRequest(0,"REJECT"," "),
                new ReviewDecisionRequest(0,"REJECT","x".repeat(1001)))) assertFalse(validator.validate(request).isEmpty());
        }
    }
}
