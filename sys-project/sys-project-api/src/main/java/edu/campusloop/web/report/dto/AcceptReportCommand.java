package edu.campusloop.web.report.dto;

import com.fasterxml.jackson.databind.JsonNode;
import edu.campusloop.common.ApiException;
import java.util.*;

public record AcceptReportCommand(int version,String reason) {
    public static AcceptReportCommand parse(JsonNode body) {
        if(body==null || !body.isObject()) throw bad();
        Set<String> fields=new HashSet<>();body.fieldNames().forEachRemaining(fields::add);
        if(!fields.equals(Set.of("version","reason"))) throw bad();
        JsonNode version=body.get("version"),reason=body.get("reason");
        if(version==null || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue()<0 ||
            reason==null || !reason.isTextual() || reason.textValue().trim().isEmpty() || reason.textValue().trim().length()>1000) throw bad();
        return new AcceptReportCommand(version.intValue(),reason.textValue().trim());
    }
    private static ApiException bad() {return new ApiException(400,"举报受理请求字段或类型不正确");}
}
