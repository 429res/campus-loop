package edu.campusloop;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campusloop.web.exchange.dto.CreateExchangeRequest;
import edu.campusloop.common.ApiException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreateExchangeRequestTest {
    private final ObjectMapper json=new ObjectMapper();
    private final String valid="""
        {"ruleVersion":"independent-v2","idempotencyKey":"fictional-key","flows":[
        {"itemId":11,"itemVersion":3,"demandId":502,"demandVersion":4},
        {"itemId":22,"itemVersion":5,"demandId":501,"demandVersion":6}]}
        """;
    @Test void acceptsOnlyExplicitIntegralPreconditionsAndCanonicalizes() throws Exception {
        var cmd=json.readValue(valid,CreateExchangeRequest.class).command();
        assertEquals(11,cmd.flows().get(0).itemId());assertEquals(64,cmd.requestDigest().length());
        assertEquals(409,assertThrows(ApiException.class,()->json.readValue(valid.replace("independent-v2","legacy-v1"),CreateExchangeRequest.class).command()).getStatus());
    }
    @Test void rejectsMissingUnknownSpoofedAndCoercedFields() {
        for(String text:new String[]{"null","[]","{}",valid.replace("\"itemVersion\":3,",""),
            valid.replace("\"itemVersion\":3","\"itemVersion\":3.0"),valid.replace("\"itemVersion\":3","\"itemVersion\":\"3\""),
            valid.replace("\"itemVersion\":3","\"itemVersion\":2147483648"),valid.replace("\"itemId\":11","\"itemId\":9223372036854775808"),
            valid.replace("\"itemId\":11","\"ownerId\":101,\"itemId\":11"),valid.replace("\"ruleVersion\":","\"initiatorId\":101,\"ruleVersion\":"),
            valid.replace("\"demandVersion\":4","\"demandVersion\":null"),valid.replace("\"flows\":[","\"flows\":null,\"ignored\":[")})
            assertThrows(Exception.class,()->json.readValue(text,CreateExchangeRequest.class).command());
    }
}
