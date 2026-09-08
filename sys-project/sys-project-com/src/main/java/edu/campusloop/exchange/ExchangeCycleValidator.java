package edu.campusloop.exchange;

import edu.campusloop.common.ApiException;
import edu.campusloop.matching.*;
import java.util.*;

/** B domain rules. A MUST supply fresh locked database rows, including all associated active demands. */
public final class ExchangeCycleValidator {
    public record ValidatedCycle(ExchangeCreationCommand command, IndependentDemandMatcher.Recommendation recommendation) {}

    public ValidatedCycle validate(long initiatorId, ExchangeCreationCommand command, IndependentMatchingInput database) {
        if (initiatorId < 1) throw new ApiException(403, "发起人身份无效");
        Set<Long> requestedIds = new HashSet<>();
        command.flows().forEach(f -> requestedIds.add(f.itemId()));
        List<IndependentMatchingInput.Offer> offers = database.offers().stream()
            .filter(o -> requestedIds.contains(o.id())).toList();
        // Conflicting or repeated database rows must never resolve by choosing an arbitrary owner.
        if (offers.size() != requestedIds.size() || offers.stream().map(IndependentMatchingInput.Offer::id).distinct().count() != offers.size())
            throw stale();
        if (offers.stream().noneMatch(o -> o.ownerId() == initiatorId))
            throw new ApiException(403, "发起人必须提供本环中的一件物品");
        if (offers.stream().map(IndependentMatchingInput.Offer::ownerId).distinct().count() != offers.size())
            throw new ApiException(400, "交换环的用户必须唯一且每人提供一件物品");
        for (var expected : command.flows()) {
            var offer = offers.stream().filter(o -> o.id() == expected.itemId()).findFirst().orElseThrow(ExchangeCycleValidator::stale);
            // Reserving and later cancelling, expiring or completing each advance the item version.
            // Refuse an exchange whose reservation would consume the final available increment.
            if (offer.version() != expected.itemVersion() || offer.version() >= Integer.MAX_VALUE - 1) throw stale();
            List<IndependentMatchingInput.Demand> demands = database.demands().stream()
                .filter(d -> d.id() == expected.demandId()).toList();
            // Completion fulfills the selected demand and advances its version once.
            if (demands.size() != 1 || demands.get(0).version() != expected.demandVersion()
                || demands.get(0).version() == Integer.MAX_VALUE) throw stale();
        }
        String id = IndependentDemandMatcher.RULE_VERSION + ":cycle-" + String.join("-",
            command.flows().stream().map(f -> Long.toString(f.itemId())).toList());
        IndependentDemandMatcher.Recommendation match;
        try {
            match = new IndependentDemandMatcher().find(new IndependentMatchingInput(offers, database.demands()), initiatorId)
                .stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow(ExchangeCycleValidator::stale);
        } catch (IndependentDemandMatcher.MatchingLimitException limit) { throw new ApiException(422, limit.getMessage()); }
        for (int i=0; i<command.flows().size(); i++) {
            if (match.flows().get(i).demandId() != command.flows().get(i).demandId()) throw stale();
        }
        return new ValidatedCycle(command, match);
    }

    private static ApiException stale() { return new ApiException(409, "物品、需求或推荐已变化，请刷新后重新选择"); }
}
