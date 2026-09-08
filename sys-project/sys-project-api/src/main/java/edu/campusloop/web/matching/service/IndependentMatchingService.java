package edu.campusloop.web.matching.service;

import edu.campusloop.common.ApiException;
import edu.campusloop.matching.IndependentDemandMatcher;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class IndependentMatchingService {
    private final IndependentMatchingSnapshotService snapshots;
    private final IndependentDemandMatcher matcher = new IndependentDemandMatcher();

    public IndependentMatchingService(IndependentMatchingSnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    public record Result(String ruleVersion, List<IndependentDemandMatcher.Recommendation> recommendations) {}

    public Result recommendations(long viewerId, String ruleVersion) {
        if (ruleVersion != null && !IndependentDemandMatcher.RULE_VERSION.equals(ruleVersion))
            throw new ApiException(400, "不支持的独立需求匹配规则版本");
        try {
            return new Result(IndependentDemandMatcher.RULE_VERSION, matcher.find(snapshots.snapshot(), viewerId));
        } catch (IndependentDemandMatcher.MatchingLimitException limit) {
            throw new ApiException(422, limit.getMessage());
        }
    }
}
