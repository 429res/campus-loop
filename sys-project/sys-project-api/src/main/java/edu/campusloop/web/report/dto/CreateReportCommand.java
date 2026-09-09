package edu.campusloop.web.report.dto;

import com.fasterxml.jackson.databind.JsonNode;
import edu.campusloop.common.ApiException;
import java.util.*;

public record CreateReportCommand(String targetType,long targetId,String reason,List<String> evidenceUploadIds,String idempotencyKey) {
    private static final Set<String> FIELDS=Set.of("targetType","targetId","reason","evidenceUploadIds","idempotencyKey");

    public static CreateReportCommand parse(JsonNode body) {
        if(body==null || !body.isObject()) throw bad();
        Set<String> fields=new HashSet<>();body.fieldNames().forEachRemaining(fields::add);
        if(!fields.equals(FIELDS)) throw bad();
        String targetType=text(body,"targetType",16),reason=text(body,"reason",1000),key=text(body,"idempotencyKey",64);
        if(!"ITEM".equals(targetType)) throw new ApiException(400,"当前仅支持 ITEM 举报；交换争议请使用交换争议接口");
        JsonNode target=body.get("targetId");
        if(target==null || !target.isIntegralNumber() || !target.canConvertToLong() || target.longValue()<1) throw bad();
        if(!key.matches("[a-z0-9._:-]{1,64}")) throw bad();
        JsonNode evidence=body.get("evidenceUploadIds");
        if(evidence==null || !evidence.isArray() || evidence.size()>5) throw bad();
        List<String> ids=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(JsonNode entry:evidence) {
            if(!entry.isTextual()) throw bad();
            String id=entry.textValue();
            try {if(!UUID.fromString(id).toString().equals(id)) throw bad();}
            catch(IllegalArgumentException malformed) {throw bad();}
            if(!seen.add(id)) throw bad();ids.add(id);
        }
        return new CreateReportCommand(targetType,target.longValue(),reason,List.copyOf(ids),key);
    }
    private static String text(JsonNode body,String field,int max) {
        JsonNode value=body.get(field);
        if(value==null || !value.isTextual() || value.textValue().trim().isEmpty() || value.textValue().trim().length()>max) throw bad();
        return value.textValue().trim();
    }
    private static ApiException bad() {return new ApiException(400,"举报请求字段或类型不正确");}
}
