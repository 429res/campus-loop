package edu.campusloop.web.report.dto;

import com.fasterxml.jackson.databind.JsonNode;
import edu.campusloop.common.ApiException;
import java.util.*;

public record DecideReportCommand(int version,String decision,String reason) {
    public static DecideReportCommand parse(JsonNode body) {
        if(body==null || !body.isObject()) throw bad();
        Set<String> fields=new HashSet<>();body.fieldNames().forEachRemaining(fields::add);
        if(!fields.equals(Set.of("version","decision","reason"))) throw bad();
        JsonNode version=body.get("version"),decision=body.get("decision"),reason=body.get("reason");
        if(version==null || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue()<0 ||
            decision==null || !decision.isTextual() || !Set.of("UPHELD","DISMISSED").contains(decision.textValue()) ||
            reason==null || !reason.isTextual() || reason.textValue().trim().isEmpty() || reason.textValue().trim().length()>1000) throw bad();
        return new DecideReportCommand(version.intValue(),decision.textValue(),reason.textValue().trim());
    }
    private static ApiException bad() {return new ApiException(400,"举报处理请求字段或类型不正确");}
}
