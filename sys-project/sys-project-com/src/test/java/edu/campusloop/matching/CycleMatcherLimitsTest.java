package edu.campusloop.matching;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class CycleMatcherLimitsTest {
    private final CycleMatcher matcher = new CycleMatcher();

    @Test void acceptsExactlyTwoHundredUnmatchedCandidates() {
        assertTrue(matcher.find(unmatched(200)).isEmpty());
    }

    @Test void rejectsCandidateLimitEvenWhenThereAreNoCycles() {
        var limit = assertThrows(MatchingLimitException.class, () -> matcher.find(unmatched(201)));
        assertTrue(limit.getMessage().contains("200"));
    }

    @Test void candidateLimitKeepsDuplicateConflictAndStatusFiltering() {
        var offers = new ArrayList<>(unmatched(201));
        offers.addAll(offers.subList(0, 200));
        // This conflicting ID is excluded, leaving exactly 200 eligible candidates.
        offers.add(offer(201, 999, 1, 2, "HIDDEN"));
        offers.add(offer(202, 202, 1, 2, "RESERVED"));
        assertTrue(matcher.find(offers).isEmpty());
    }

    @Test void returnsAllOneThousandCyclesAtTheResultBoundary() {
        var result = matcher.find(oneThousandCycles());
        assertEquals(CycleMatcher.MAX_RECOMMENDATIONS, result.size());
        assertEquals(1000, result.stream().map(CycleMatcher.Recommendation::id).distinct().count());
        assertTrue(result.stream().allMatch(cycle -> cycle.length() == 3));
        assertTrue(result.stream().allMatch(cycle -> cycle.score() == 70));
        assertTrue(result.stream().allMatch(cycle -> cycle.participants().size() == 3 && cycle.flows().size() == 3));
        assertEquals(result.stream().sorted(Comparator.comparing(CycleMatcher.Recommendation::id)).toList(), result);
    }

    @Test void rejectsOneThousandAndOneCyclesInsteadOfReturningPartialResults() {
        var offers = oneThousandCycles();
        // Disjoint two-way match plus 10 * 10 * 10 directed three-way matches.
        offers.add(offer(31, 31, 4, 5, "AVAILABLE"));
        offers.add(offer(32, 32, 5, 4, "AVAILABLE"));
        var limit = assertThrows(MatchingLimitException.class, () -> matcher.find(offers));
        assertTrue(limit.getMessage().contains("1000"));
    }

    @Test void denseLegalCandidateSetsFailWithLimitException() {
        for (int count : List.of(100, 200)) {
            var offers = IntStream.rangeClosed(1, count)
                .mapToObj(id -> offer(id, id, 1, 1, "AVAILABLE")).toList();
            var limit = assertThrows(MatchingLimitException.class, () -> matcher.find(offers));
            assertTrue(limit.getMessage().contains("1000"));
        }
    }

    @Test void preservesCompleteBoundaryResultWhenInputIsReorderedAndDuplicated() {
        var offers = oneThousandCycles();
        var expected = matcher.find(offers);
        Collections.reverse(offers);
        offers.addAll(new ArrayList<>(offers));
        assertEquals(expected, matcher.find(offers));
    }

    @Test void preservesScoreLengthAndIdentifierOrderingBelowLimit() {
        var offers = new ArrayList<CycleMatcher.Offer>();
        offers.add(offer(1, 1, 1, 1, "AVAILABLE"));
        offers.add(offer(2, 2, 1, 1, "AVAILABLE"));
        offers.add(offer(3, 3, 1, 1, "AVAILABLE"));
        offers.add(new CycleMatcher.Offer(4, 4, "同学4", "物品4", 1, "分类1", 1,
            Set.of(), Set.of(), "AVAILABLE"));
        var result = matcher.find(offers);
        assertEquals(14, result.size()); // 6 pairs and 8 directed triples.
        assertEquals(List.of("cycle-1-2", "cycle-1-3", "cycle-2-3", "cycle-1-2-3", "cycle-1-3-2"),
            result.stream().limit(5).map(CycleMatcher.Recommendation::id).toList());
        assertTrue(result.stream().limit(5).allMatch(cycle -> cycle.score() == 70));
        assertTrue(result.stream().skip(5).limit(6).allMatch(cycle -> cycle.score() == 63));
        assertTrue(result.stream().skip(11).allMatch(cycle -> cycle.score() == 60));
    }

    private List<CycleMatcher.Offer> unmatched(int count) {
        return IntStream.rangeClosed(1, count)
            .mapToObj(id -> offer(id, id, 1, 2, "AVAILABLE")).toList();
    }

    private ArrayList<CycleMatcher.Offer> oneThousandCycles() {
        var offers = new ArrayList<CycleMatcher.Offer>();
        for (int id = 1; id <= 30; id++) {
            int category = (id - 1) / 10 + 1;
            int wanted = category == 1 ? 3 : category - 1;
            offers.add(offer(id, id, category, wanted, "AVAILABLE"));
        }
        return offers;
    }

    private CycleMatcher.Offer offer(long id, long owner, long category, long wanted, String status) {
        return new CycleMatcher.Offer(id, owner, "同学" + owner, "物品" + id, category, "分类" + category,
            wanted, Set.of(" Campus "), Set.of("campus"), status);
    }
}
