package edu.campusloop.matching;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static edu.campusloop.matching.IndependentMatchingInput.*;

class IndependentDemandMatcherTest {
    private final IndependentDemandMatcher matcher = new IndependentDemandMatcher();

    @Test void explainsEachTwoWayFlowUsingTheReceiversAssociatedDemand() {
        var input = pair();
        var result = matcher.find(input, 1);
        assertEquals(1, result.size());
        var recommendation = result.get(0);
        assertEquals("independent-v2:cycle-10-20", recommendation.id());
        assertEquals("independent-v2", recommendation.ruleVersion());
        assertEquals(2, recommendation.length());
        assertEquals(60, recommendation.score());
        assertEquals(List.of(1L, 2L), recommendation.participants().stream().map(IndependentDemandMatcher.Participant::userId).toList());
        var first = recommendation.flows().get(0);
        assertEquals(1, first.fromUserId()); assertEquals(2, first.toUserId()); assertEquals(10, first.itemId());
        assertEquals(102, first.demandId()); assertEquals(List.of(102L), first.matchedDemandIds());
        assertEquals(1, first.matchedCategoryId()); assertEquals("分类1", first.matchedCategoryName());
        assertEquals(List.of(), first.matchedTags());
        assertEquals("满足同学2的独立需求 #102：「分类1」分类；分类匹配，暂无共同偏好标签", first.reason());
        var second = recommendation.flows().get(1);
        assertEquals(2, second.fromUserId()); assertEquals(1, second.toUserId()); assertEquals(20, second.itemId());
        assertEquals(101, second.demandId()); assertEquals(2, second.matchedCategoryId());
        assertEquals(result, matcher.find(input, 2));
        assertTrue(matcher.find(input, 3).isEmpty());
    }

    @Test void preservesThreeWayDirectionForEveryViewerIncludingTheNonMinimumItem() {
        var input = new IndependentMatchingInput(List.of(offer(10, 1, 1), offer(20, 2, 2), offer(30, 3, 3)),
            List.of(demand(101, 1, 3, Set.of(), Set.of(10L)), demand(102, 2, 1, Set.of(), Set.of(20L)),
                demand(103, 3, 2, Set.of(), Set.of(30L))));
        var result = matcher.find(input, 3);
        assertEquals(1, result.size());
        assertEquals("independent-v2:cycle-10-20-30", result.get(0).id());
        assertEquals(List.of(1L, 2L, 3L), result.get(0).flows().stream().map(IndependentDemandMatcher.Flow::fromUserId).toList());
        assertEquals(List.of(2L, 3L, 1L), result.get(0).flows().stream().map(IndependentDemandMatcher.Flow::toUserId).toList());
        assertEquals(List.of(102L, 103L, 101L), result.get(0).flows().stream().map(IndependentDemandMatcher.Flow::demandId).toList());
        assertEquals(result, matcher.find(input, 1)); assertEquals(result, matcher.find(input, 2));
        assertEquals(result, matcher.find(reordered(input), 3));
    }

    @Test void equalScoresKeepLengthAndStringIdOrderAndDistinctReverseRingsForTheViewer() {
        List<Offer> offers = new ArrayList<>(); List<Demand> demands = new ArrayList<>();
        for (long id : List.of(2L, 10L, 11L, 12L)) {
            offers.add(offer(id, id, 1)); demands.add(demand(100 + id, id, 1, Set.of(), Set.of(id)));
        }
        var input = new IndependentMatchingInput(offers, demands);
        var result = matcher.find(input, 11);
        assertEquals(List.of("independent-v2:cycle-10-11", "independent-v2:cycle-11-12", "independent-v2:cycle-2-11",
            "independent-v2:cycle-10-11-12", "independent-v2:cycle-10-12-11", "independent-v2:cycle-2-10-11",
            "independent-v2:cycle-2-11-10", "independent-v2:cycle-2-11-12", "independent-v2:cycle-2-12-11"),
            result.stream().map(IndependentDemandMatcher.Recommendation::id).toList());
        assertTrue(result.stream().allMatch(r -> r.participants().stream().anyMatch(p -> p.userId() == 11)));
        assertEquals(List.of(10L, 11L, 2L), result.stream().filter(r -> r.id().endsWith("cycle-2-10-11")).findFirst().orElseThrow()
            .flows().stream().map(IndependentDemandMatcher.Flow::toUserId).toList());
        assertEquals(List.of(11L, 10L, 2L), result.stream().filter(r -> r.id().endsWith("cycle-2-11-10")).findFirst().orElseThrow()
            .flows().stream().map(IndependentDemandMatcher.Flow::toUserId).toList());
        assertEquals(result, matcher.find(reordered(input), 11));
    }

    @Test void viewerMayOfferDifferentItemsInDifferentResultsButNeverTwoInOneRing() {
        var input = new IndependentMatchingInput(List.of(offer(10, 1, 1), offer(11, 1, 1), offer(20, 2, 2)),
            List.of(demand(101, 1, 2, Set.of(), Set.of(10L, 11L)), demand(102, 2, 1, Set.of(), Set.of(20L))));
        var result = matcher.find(input, 1);
        assertEquals(List.of("independent-v2:cycle-10-20", "independent-v2:cycle-11-20"),
            result.stream().map(IndependentDemandMatcher.Recommendation::id).toList());
        assertTrue(result.stream().allMatch(r -> r.length() == 2 && r.participants().stream().map(IndependentDemandMatcher.Participant::userId).distinct().count() == 2));
        var sameOwner = new IndependentMatchingInput(List.of(offer(10, 1, 1), offer(20, 1, 2)),
            List.of(demand(101, 1, 2, Set.of(), Set.of(10L)), demand(102, 1, 1, Set.of(), Set.of(20L))));
        assertTrue(matcher.find(sameOwner, 1).isEmpty());
    }

    @Test void onlyActiveDemandsWithCurrentOwnershipAndTheSelectedOutgoingItemCanMatch() {
        var original = pair();
        for (String status : List.of("INACTIVE", "DELETED", "UNKNOWN")) {
            var inactive = new Demand(102, 2, 1, Set.of(), Set.of(20L), status, 0);
            assertTrue(matcher.find(new IndependentMatchingInput(original.offers(), List.of(original.demands().get(0), inactive)), 1).isEmpty(), status);
        }
        for (Demand invalid : List.of(demand(102, 3, 1, Set.of(), Set.of(20L)),
            demand(102, 2, 1, Set.of(), Set.of(999L)), demand(102, 2, 1, Set.of(), Set.of()),
            demand(102, 2, 3, Set.of(), Set.of(20L)))) {
            assertTrue(matcher.find(new IndependentMatchingInput(original.offers(), List.of(original.demands().get(0), invalid)), 1).isEmpty());
        }
        // A user-level demand must not be silently applied to another unassociated item of the same owner.
        var wrongItem = new IndependentMatchingInput(List.of(offer(10, 1, 1), offer(20, 2, 2), offer(21, 2, 3)),
            List.of(original.demands().get(0), demand(102, 2, 1, Set.of(), Set.of(21L))));
        assertTrue(matcher.find(wrongItem, 1).isEmpty());
        var transferred = new IndependentMatchingInput(List.of(offer(10, 1, 1), offer(20, 3, 2)), original.demands());
        assertTrue(matcher.find(transferred, 1).isEmpty());
    }

    @Test void unavailableDisabledOrHeldItemsCannotParticipate() {
        var original = pair();
        for (String status : List.of("RESERVED", "EXCHANGED", "HIDDEN", "DRAFT", "PENDING_REVIEW", "UNKNOWN")) {
            var unavailable = new Offer(20, 2, "同学2", "物品20", 2, "分类2", Set.of(), status, "ACTIVE", false);
            assertTrue(matcher.find(new IndependentMatchingInput(List.of(original.offers().get(0), unavailable), original.demands()), 1).isEmpty(), status);
        }
        for (String status : List.of("DISABLED", "UNKNOWN")) {
            var disabled = new Offer(20, 2, "同学2", "物品20", 2, "分类2", Set.of(), "AVAILABLE", status, false);
            assertTrue(matcher.find(new IndependentMatchingInput(List.of(original.offers().get(0), disabled), original.demands()), 1).isEmpty(), status);
        }
        var held = new Offer(20, 2, "同学2", "物品20", 2, "分类2", Set.of(), "AVAILABLE", "ACTIVE", true);
        assertTrue(matcher.find(new IndependentMatchingInput(List.of(original.offers().get(0), held), original.demands()), 1).isEmpty());
    }

    @Test void missingDemandsOrItemsReturnEmptyWithoutInventingLegacyNeeds() {
        assertTrue(matcher.find(new IndependentMatchingInput(List.of(), List.of()), 1).isEmpty());
        assertTrue(matcher.find(new IndependentMatchingInput(List.of(), pair().demands()), 1).isEmpty());
        assertTrue(matcher.find(new IndependentMatchingInput(pair().offers(), List.of()), 1).isEmpty());
        assertTrue(matcher.find(new IndependentMatchingInput(List.of(offer(10, 1, 1)), pair().demands()), 1).isEmpty());
        assertTrue(matcher.find(new IndependentMatchingInput(List.of(), List.of(demand(101, 1, 2, Set.of(), Set.of()))), 1).isEmpty());
    }

    @Test void duplicateIdsAreDeduplicatedOnlyWhenTheWholeSnapshotAgrees() {
        var original = pair();
        List<Offer> repeatedOffers = new ArrayList<>(original.offers()); repeatedOffers.add(original.offers().get(0));
        List<Demand> repeatedDemands = new ArrayList<>(original.demands()); repeatedDemands.add(original.demands().get(0));
        assertEquals(matcher.find(original, 1), matcher.find(new IndependentMatchingInput(repeatedOffers, repeatedDemands), 1));
        repeatedOffers.add(new Offer(10, 1, "同学1", "物品10", 1, "分类1", Set.of(), "HIDDEN", "ACTIVE", false));
        assertTrue(matcher.find(new IndependentMatchingInput(repeatedOffers, repeatedDemands), 1).isEmpty());
        repeatedDemands.add(new Demand(101, 1, 2, Set.of(), Set.of(10L), "INACTIVE", 0));
        assertTrue(matcher.find(new IndependentMatchingInput(original.offers(), repeatedDemands), 1).isEmpty());
        var differentVersion = new Demand(101, 1, 2, Set.of(), Set.of(10L), "ACTIVE", 1);
        assertTrue(matcher.find(new IndependentMatchingInput(original.offers(), List.of(original.demands().get(0),
            original.demands().get(1), differentVersion)), 1).isEmpty());
    }

    @Test void multipleDemandMatchesAreMergedIntoOneFlowAndChooseTheBestCappedTagScore() {
        var input = new IndependentMatchingInput(List.of(offer(10, 1, 1, Set.of(" A ", "b", "unmatched")), offer(20, 2, 2, Set.of("r"))),
            List.of(demand(101, 1, 2, Set.of("r"), Set.of(10L)), demand(201, 2, 1, Set.of("a"), Set.of(20L)),
                demand(202, 2, 1, Set.of(" a ", "B", "b", " "), Set.of(20L)), demand(203, 2, 1, Set.of("b"), Set.of(20L)),
                demand(204, 2, 1, Set.of("z"), Set.of(20L)), demand(205, 2, 9, Set.of("a", "b"), Set.of(20L)),
                new Demand(206, 2, 1, Set.of("a", "b"), Set.of(20L), "INACTIVE", 0)));
        var result = matcher.find(input, 1);
        assertEquals(1, result.size()); assertEquals(75, result.get(0).score());
        var flow = result.get(0).flows().get(0);
        assertEquals(202, flow.demandId()); assertEquals(List.of(201L, 202L, 203L, 204L), flow.matchedDemandIds());
        assertEquals(List.of("a", "b"), flow.matchedTags());
        assertTrue(flow.reason().endsWith("偏好标签：a、b"));
        assertEquals(result, matcher.find(reordered(input), 1));
    }

    @Test void tiesUseLowestDemandIdAndNeverUnionAlternativeDemandTagsForScoring() {
        var input = new IndependentMatchingInput(List.of(offer(10, 1, 1, Set.of("a1", "a2", "b1", "b2")), offer(20, 2, 2)),
            List.of(demand(101, 1, 2, Set.of(), Set.of(10L)), demand(202, 2, 1, Set.of("b1", "b2"), Set.of(20L)),
                demand(201, 2, 1, Set.of("a1", "a2"), Set.of(20L))));
        var recommendation = matcher.find(input, 1).get(0);
        assertEquals(70, recommendation.score());
        assertEquals(201, recommendation.flows().get(0).demandId());
        assertEquals(List.of("a1", "a2"), recommendation.flows().get(0).matchedTags());
        assertEquals(List.of(201L, 202L), recommendation.flows().get(0).matchedDemandIds());
        var noHits = matcher.find(new IndependentMatchingInput(pair().offers(), input.demands()), 1).get(0);
        assertEquals(60, noHits.score()); assertEquals(201, noHits.flows().get(0).demandId());
    }

    @Test void scoringCapsEachFlowAtFourWhileSelectedReasonPreservesAllEightTags() {
        var offers = List.of(offer(10, 1, 1, tags("a", 8)), offer(20, 2, 2, tags("b", 8)));
        var cappedTie = new IndependentMatchingInput(offers, List.of(demand(101, 1, 2, tags("b", 8), Set.of(10L)),
            demand(201, 2, 1, tags("a", 4), Set.of(20L)), demand(202, 2, 1, tags("a", 8), Set.of(20L))));
        var first = matcher.find(cappedTie, 1).get(0);
        assertEquals(100, first.score()); assertEquals(201, first.flows().get(0).demandId());
        assertEquals(4, first.flows().get(0).matchedTags().size());
        assertEquals(8, first.flows().get(1).matchedTags().size());
        var eightSelected = new IndependentMatchingInput(offers, List.of(demand(101, 1, 2, Set.of("b1"), Set.of(10L)),
            demand(201, 2, 1, tags("a", 8), Set.of(20L)), demand(202, 2, 1, tags("a", 4), Set.of(20L))));
        var second = matcher.find(eightSelected, 1).get(0);
        assertEquals(85, second.score());
        assertEquals(List.of("a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8"), second.flows().get(0).matchedTags());
    }

    @Test void threeWayScoresPreserveEveryExistingRoundingBoundary() {
        int[] expected = {60, 63, 67, 70, 73, 77, 80, 83, 87, 90, 93, 97, 100};
        var offers = List.of(offer(10, 1, 1, tags("a", 4)), offer(20, 2, 2, tags("b", 4)), offer(30, 3, 3, tags("c", 4)));
        for (int total = 0; total < expected.length; total++) {
            var demands = List.of(demand(101, 1, 3, tags("c", Math.max(total - 8, 0)), Set.of(10L)),
                demand(102, 2, 1, tags("a", Math.min(total, 4)), Set.of(20L)),
                demand(103, 3, 2, tags("b", Math.min(Math.max(total - 4, 0), 4)), Set.of(30L)));
            var result = matcher.find(new IndependentMatchingInput(offers, demands), 3);
            assertEquals(1, result.size()); assertEquals(expected[total], result.get(0).score(), "total hits=" + total);
        }
    }

    @Test void scoreRemainsPrimaryEvenWhenTheBetterRingIsLonger() {
        var offers = List.of(offer(1, 1, 1), offer(2, 2, 2), offer(10, 1, 10, Set.of("hit")),
            offer(11, 3, 11, Set.of("hit")), offer(12, 4, 12, Set.of("hit")));
        var demands = List.of(demand(101, 1, 2, Set.of(), Set.of(1L)), demand(102, 2, 1, Set.of(), Set.of(2L)),
            demand(110, 1, 12, Set.of("hit"), Set.of(10L)), demand(111, 3, 10, Set.of("hit"), Set.of(11L)),
            demand(112, 4, 11, Set.of("hit"), Set.of(12L)));
        var result = matcher.find(new IndependentMatchingInput(offers, demands), 1);
        assertEquals(List.of("independent-v2:cycle-10-11-12", "independent-v2:cycle-1-2"),
            result.stream().map(IndependentDemandMatcher.Recommendation::id).toList());
        assertEquals(List.of(70, 60), result.stream().map(IndependentDemandMatcher.Recommendation::score).toList());
    }

    @Test void demandEditVersionIsNotTheRecommendationRuleVersion() {
        var original = pair();
        var newer = new Demand(101, 1, 2, Set.of(), Set.of(10L), "ACTIVE", 37);
        assertEquals(matcher.find(original, 1), matcher.find(new IndependentMatchingInput(original.offers(),
            List.of(newer, original.demands().get(1))), 1));
        assertEquals("independent-v2", matcher.find(original, 1).get(0).ruleVersion());
    }

    @Test void itemLimitAcceptsTwoHundredAndRejectsTwoHundredOneWithoutTruncation() {
        List<Offer> offers = new ArrayList<>();
        for (long id = 1; id <= 200; id++) offers.add(offer(id, id, id));
        assertTrue(matcher.find(new IndependentMatchingInput(offers, List.of()), 1).isEmpty());
        offers.add(offers.get(0));
        assertTrue(matcher.find(new IndependentMatchingInput(offers, List.of()), 1).isEmpty());
        offers.add(new Offer(201, 201, "同学201", "物品201", 1, "分类1", Set.of(), "AVAILABLE", "ACTIVE", true));
        assertTrue(matcher.find(new IndependentMatchingInput(offers, List.of()), 1).isEmpty());
        offers.add(offer(202, 202, 1));
        var failure = assertThrows(IndependentDemandMatcher.MatchingLimitException.class,
            () -> matcher.find(new IndependentMatchingInput(offers, List.of()), 1));
        assertTrue(failure.getMessage().contains("200 件"));
    }

    @Test void resultLimitAcceptsExactlyOneThousandAndRejectsTheNextCompleteRing() {
        var atLimit = thousandResultGraph();
        assertEquals(1000, matcher.find(atLimit, 1).size());
        List<Demand> extra = new ArrayList<>(atLimit.demands());
        // Restoring this one directed edge adds exactly the viewer->2->3->viewer ring.
        extra.add(demand(99999, 3, 2, Set.of(), Set.of(3L)));
        var failure = assertThrows(IndependentDemandMatcher.MatchingLimitException.class,
            () -> matcher.find(new IndependentMatchingInput(atLimit.offers(), extra), 1));
        assertTrue(failure.getMessage().contains("1000 个"));
    }

    @Test void denseUnrelatedRingsDoNotConsumeTheViewersRecommendationBudget() {
        var original = pair();
        List<Offer> offers = new ArrayList<>(original.offers()); List<Demand> demands = new ArrayList<>(original.demands());
        for (long id = 100; id < 150; id++) {
            offers.add(offer(id, id, 9)); demands.add(demand(1000 + id, id, 9, Set.of(), Set.of(id)));
        }
        // The other fifty users form far more than 1000 rings, all unrelated to either pair participant.
        assertEquals(matcher.find(original, 1), matcher.find(new IndependentMatchingInput(offers, demands), 1));
    }

    @Test void explanationLimitCountsRepeatedReturnedReferencesAndRejectsOverflow() {
        var atLimit = repeatedExplanationGraph(2000);
        var result = matcher.find(atLimit, 1);
        assertEquals(4, result.size());
        assertEquals(20000, result.stream().flatMap(r -> r.flows().stream()).mapToInt(f -> f.matchedDemandIds().size()).sum());
        var firstIncoming = result.get(0).flows().stream().filter(f -> f.toUserId() == 1).findFirst().orElseThrow();
        var secondIncoming = result.get(1).flows().stream().filter(f -> f.toUserId() == 1).findFirst().orElseThrow();
        assertSame(firstIncoming.matchedDemandIds(), secondIncoming.matchedDemandIds());
        List<Demand> extra = new ArrayList<>(atLimit.demands()); extra.add(demand(99999, 1, 1, Set.of(), Set.of(1L)));
        var failure = assertThrows(IndependentDemandMatcher.MatchingLimitException.class,
            () -> matcher.find(new IndependentMatchingInput(atLimit.offers(), extra), 1));
        assertTrue(failure.getMessage().contains("20000 个"));
    }

    @Test void returnedDataIsImmutableAndInputRemainsUnchanged() {
        var input = pair(); var before = pair();
        var result = matcher.find(input, 1);
        assertEquals(before, input);
        assertThrows(UnsupportedOperationException.class, () -> result.add(result.get(0)));
        var recommendation = result.get(0); var flow = recommendation.flows().get(0);
        assertThrows(UnsupportedOperationException.class, () -> recommendation.participants().clear());
        assertThrows(UnsupportedOperationException.class, () -> recommendation.flows().clear());
        assertThrows(UnsupportedOperationException.class, () -> flow.matchedDemandIds().add(1000L));
        assertThrows(UnsupportedOperationException.class, () -> flow.matchedTags().add("change"));
    }

    private IndependentMatchingInput pair() {
        return new IndependentMatchingInput(List.of(offer(10, 1, 1), offer(20, 2, 2)),
            List.of(demand(101, 1, 2, Set.of(), Set.of(10L)), demand(102, 2, 1, Set.of(), Set.of(20L))));
    }
    private Offer offer(long id, long owner, long category) { return offer(id, owner, category, Set.of()); }
    private Offer offer(long id, long owner, long category, Set<String> tags) {
        return new Offer(id, owner, "同学" + owner, "物品" + id, category, "分类" + category, tags, "AVAILABLE", "ACTIVE", false);
    }
    private Demand demand(long id, long owner, long category, Set<String> tags, Set<Long> offeredItemIds) {
        return new Demand(id, owner, category, tags, offeredItemIds, "ACTIVE", 0);
    }
    private Set<String> tags(String prefix, int count) {
        Set<String> tags = new HashSet<>(); for (int i = 1; i <= count; i++) tags.add(prefix + i); return tags;
    }
    private IndependentMatchingInput reordered(IndependentMatchingInput input) {
        List<Offer> offers = new ArrayList<>(input.offers()); Collections.reverse(offers); offers.add(offers.get(0));
        List<Demand> demands = new ArrayList<>(input.demands()); Collections.reverse(demands); demands.add(demands.get(0));
        return new IndependentMatchingInput(offers, demands);
    }
    private IndependentMatchingInput thousandResultGraph() {
        List<Offer> offers = new ArrayList<>(); List<Demand> demands = new ArrayList<>();
        for (long receiver = 1; receiver <= 33; receiver++) {
            offers.add(offer(receiver, receiver, receiver));
            for (long provider = 1; provider <= 33; provider++) {
                if (receiver == provider || provider == 2 && receiver >= 3 && receiver <= 26) continue;
                demands.add(demand(10000 + receiver * 100 + provider, receiver, provider, Set.of(), Set.of(receiver)));
            }
        }
        return new IndependentMatchingInput(offers, demands);
    }
    private IndependentMatchingInput repeatedExplanationGraph(int demandsPerUser) {
        List<Offer> offers = new ArrayList<>(); List<Demand> demands = new ArrayList<>();
        for (long owner = 1; owner <= 3; owner++) {
            offers.add(offer(owner, owner, 1));
            for (int i = 0; i < demandsPerUser; i++) demands.add(demand(owner * 10000 + i, owner, 1, Set.of(), Set.of(owner)));
        }
        return new IndependentMatchingInput(offers, demands);
    }
}
