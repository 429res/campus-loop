package edu.campusloop.web.history.dto;
import com.fasterxml.jackson.databind.JsonNode;
import edu.campusloop.common.ApiException;
import java.util.*;
public record HistoryVerificationCommand(int version,String snapshotHash,String idempotencyKey,String decision,String scope,String reason) {
    public static HistoryVerificationCommand parse(JsonNode body) {
        if(body==null || !body.isObject()) throw bad();
        Set<String> fields=new HashSet<>();body.fieldNames().forEachRemaining(fields::add);
        if(!fields.equals(Set.of("version","snapshotHash","idempotencyKey","decision","scope","reason"))) throw bad();
        var v=body.get("version");if(!v.isIntegralNumber() || !v.canConvertToInt() || v.intValue()<0) throw bad();
        String hash=text(body,"snapshotHash",64),key=text(body,"idempotencyKey",64),decision=text(body,"decision",16);
        if(!hash.matches("[a-f0-9]{64}") || !key.matches("[A-Za-z0-9._:-]{1,64}") || !Set.of("APPROVED","REJECTED").contains(decision)) throw bad();
        return new HistoryVerificationCommand(v.intValue(),hash,key,decision,text(body,"scope",500),text(body,"reason",2000));
    }
    private static String text(JsonNode body,String field,int max) {var v=body.get(field);if(!v.isTextual() || v.textValue().trim().isEmpty() || v.textValue().trim().length()>max) throw bad();return v.textValue().trim();}
    private static ApiException bad() {return new ApiException(400,"核验请求字段或类型不正确");}
}
