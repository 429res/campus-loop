package edu.campusloop.exchange;

import edu.campusloop.common.ApiException;
import edu.campusloop.matching.IndependentDemandMatcher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Untrusted preconditions, never ownership, confirmation, or authorization. */
public record ExchangeCreationCommand(String ruleVersion, String idempotencyKey, List<ExpectedFlow> flows) {
    public record ExpectedFlow(long itemId, int itemVersion, long demandId, int demandVersion) {}

    public ExchangeCreationCommand {
        if (ruleVersion == null || idempotencyKey == null || !idempotencyKey.matches("[a-z0-9_-]{8,64}") ||
            flows == null || flows.size() < 2 || flows.size() > 3 || flows.stream().anyMatch(Objects::isNull))
            throw new ApiException(400, "创建需要规则版本、8–64位小写幂等键和2或3条有向流向");
        if (!IndependentDemandMatcher.RULE_VERSION.equals(ruleVersion))
            throw new ApiException(409, "推荐规则已失效，请重新获取独立需求推荐");
        if (flows.stream().anyMatch(f -> f.itemId() < 1 || f.demandId() < 1 || f.itemVersion() < 0 || f.demandVersion() < 0)
            || flows.stream().map(ExpectedFlow::itemId).distinct().count() != flows.size()
            || flows.stream().map(ExpectedFlow::demandId).distinct().count() != flows.size())
            throw new ApiException(400, "流向需要不同的正整数物品/需求ID和非负版本");
        int start = 0;
        for (int i=1; i<flows.size(); i++) if (flows.get(i).itemId() < flows.get(start).itemId()) start=i;
        List<ExpectedFlow> canonical = new ArrayList<>();
        for (int i=0; i<flows.size(); i++) canonical.add(flows.get((start+i)%flows.size()));
        flows = List.copyOf(canonical);
    }

    /** Scope in storage is (server initiator, key). This digest compares only canonical request content. */
    public String requestDigest() {
        StringBuilder value = new StringBuilder(ruleVersion);
        for (ExpectedFlow f : flows) value.append('|').append(f.itemId()).append(':').append(f.itemVersion())
            .append(':').append(f.demandId()).append(':').append(f.demandVersion());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
