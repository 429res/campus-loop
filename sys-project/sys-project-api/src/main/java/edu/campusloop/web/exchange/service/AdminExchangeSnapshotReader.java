package edu.campusloop.web.exchange.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campusloop.common.ApiException;
import edu.campusloop.web.exchange.entity.ExchangeRecord;
import edu.campusloop.web.exchange.vo.AdminExchangeDetail;
import edu.campusloop.web.exchange.vo.ExchangeView;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;

/** Reads only the explicitly public creation fields; no current item/demand lookup or raw JSON passthrough. */
@Component
public class AdminExchangeSnapshotReader {
    private final ObjectMapper json;
    public AdminExchangeSnapshotReader(ObjectMapper json) { this.json=json; }

    public AdminExchangeDetail.Creation read(ExchangeRecord row, ExchangeView exchange) {
        if (row.getCreationSnapshot()==null) return null;
        try {
            JsonNode root=json.readTree(row.getCreationSnapshot());
            if (root==null || !root.isObject()) throw incomplete();
            JsonNode recommendation=root.path("recommendation");
            String ruleVersion=string(recommendation,"ruleVersion");
            if (!ruleVersion.equals(row.getRuleVersion())) throw incomplete();
            JsonNode flows=recommendation.path("flows");
            if (!flows.isArray() || flows.size()!=exchange.flows().size()) throw incomplete();
            Map<Long,AdminExchangeDetail.CreationFlow> byItem=new HashMap<>();
            for (JsonNode flow:flows) {
                var value=new AdminExchangeDetail.CreationFlow(positive(flow,"itemId"),string(flow,"itemTitle"),
                    positive(flow,"fromUserId"),positive(flow,"toUserId"),positive(flow,"demandId"),
                    string(flow,"matchedCategoryName"),string(flow,"reason"));
                if (byItem.put(value.itemId(),value)!=null) throw incomplete();
            }
            List<AdminExchangeDetail.CreationFlow> ordered=new ArrayList<>();
            for (var flow:exchange.flows()) {
                var saved=byItem.get(flow.itemId());
                if (saved==null || saved.fromUserId()!=flow.fromUserId() || saved.toUserId()!=flow.toUserId()) throw incomplete();
                ordered.add(saved);
            }
            return new AdminExchangeDetail.Creation(ruleVersion,List.copyOf(ordered));
        } catch (IOException exception) { throw incomplete(); }
    }
    private static String string(JsonNode value,String field) {
        JsonNode node=value.path(field);
        if (!node.isTextual() || node.textValue().isBlank()) throw incomplete();
        return node.textValue();
    }
    private static long positive(JsonNode value,String field) {
        JsonNode node=value.path(field);
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue()<1) throw incomplete();
        return node.longValue();
    }
    private static ApiException incomplete() { return new ApiException(409,"交换创建记录不完整，请联系维护人员"); }
}
