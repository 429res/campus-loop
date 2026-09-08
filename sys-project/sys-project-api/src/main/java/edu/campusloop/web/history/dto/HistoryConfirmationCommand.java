package edu.campusloop.web.history.dto;

import com.fasterxml.jackson.databind.JsonNode;
import edu.campusloop.common.ApiException;
import java.util.*;

/** Separate strict commands: a caller cannot submit identity, roster, exchange or source. */
public record HistoryConfirmationCommand(String snapshotHash,String reason) {
    public static HistoryConfirmationCommand parse(JsonNode body,String action) {
        Set<String> expected=switch(action) {
            case "request" -> Set.of("shareEvidenceWithAllParticipants");
            case "confirm" -> Set.of("snapshotHash","acknowledged");
            case "withdraw" -> Set.of("snapshotHash","reason");
            default -> throw new IllegalArgumentException("Unknown history action");
        };
        if(body==null || !body.isObject()) throw bad();
        Set<String> fields=new HashSet<>();body.fieldNames().forEachRemaining(fields::add);
        if(!fields.equals(expected)) throw bad();
        if(action.equals("request")) {positive(body.get("shareEvidenceWithAllParticipants"));return new HistoryConfirmationCommand(null,null);}
        JsonNode hash=body.get("snapshotHash");
        if(!hash.isTextual() || !hash.textValue().matches("[a-f0-9]{64}")) throw bad();
        if(action.equals("confirm")) {positive(body.get("acknowledged"));return new HistoryConfirmationCommand(hash.textValue(),null);}
        JsonNode reason=body.get("reason");
        if(!reason.isTextual() || reason.textValue().trim().isEmpty() || reason.textValue().trim().length()>500) throw bad();
        return new HistoryConfirmationCommand(hash.textValue(),reason.textValue().trim());
    }
    private static void positive(JsonNode value) {if(!value.isBoolean() || !value.booleanValue()) throw bad();}
    private static ApiException bad() {return new ApiException(400,"确认请求字段不正确，须明确认可指定声明及证据版本");}
}
