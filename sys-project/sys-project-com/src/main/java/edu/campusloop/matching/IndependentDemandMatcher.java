package edu.campusloop.matching;

import java.util.*;
import java.util.function.ToLongFunction;

/** Pure, directed recommendations from explicitly associated independent demands. */
public final class IndependentDemandMatcher {
    public static final String RULE_VERSION = "independent-v2";
    public static final int MAX_ITEMS = 200;
    public static final int MAX_RECOMMENDATIONS = 1000;
    public static final int MAX_EXPLANATION_DEMAND_IDS = 20000;

    public record Participant(long userId, String displayName, long itemId, String itemTitle, int itemVersion) {}
    public record Flow(long fromUserId, String fromName, long toUserId, String toName,
                       long itemId, String itemTitle, long demandId, List<Long> matchedDemandIds,
                       long matchedCategoryId, String matchedCategoryName, List<String> matchedTags,
                       String reason, int demandVersion) {}
    public record Recommendation(String id, String ruleVersion, int length, int score,
                                 List<Participant> participants, List<Flow> flows, String explanation) {}

    public static final class MatchingLimitException extends RuntimeException {
        public MatchingLimitException(String message) { super(message); }
    }

    public List<Recommendation> find(IndependentMatchingInput input, long viewerId) {
        List<IndependentMatchingInput.Offer> offers = unambiguous(input.offers(), IndependentMatchingInput.Offer::id)
            .stream().filter(offer -> "AVAILABLE".equals(offer.status()) &&
                "ACTIVE".equals(offer.userStatus()) && !offer.held()).toList();
        if (offers.size() > MAX_ITEMS)
            throw new MatchingLimitException("独立需求匹配最多支持 200 件可交换物品；请先缩小候选范围");
        if (offers.stream().noneMatch(offer -> offer.ownerId() == viewerId)) return List.of();

        Map<Long, IndependentMatchingInput.Offer> byId = new HashMap<>();
        offers.forEach(offer -> byId.put(offer.id(), offer));
        Map<Long, Map<Long, List<PreparedDemand>>> byReceiver = new HashMap<>();
        for (var demand : unambiguous(input.demands(), IndependentMatchingInput.Demand::id)) {
            if (!"ACTIVE".equals(demand.status())) continue;
            PreparedDemand prepared = new PreparedDemand(demand.id(), normalized(demand.preferredTags()), demand.version());
            for (long itemId : demand.offeredItemIds()) {
                var item = byId.get(itemId);
                if (item == null || item.ownerId() != demand.ownerId()) continue;
                byReceiver.computeIfAbsent(itemId, ignored -> new HashMap<>())
                    .computeIfAbsent(demand.categoryId(), ignored -> new ArrayList<>()).add(prepared);
            }
        }

        // Each receiver/category list is shared by all matching provider edges, including its ID list.
        Map<Long, Map<Long, DemandChoices>> choices = new HashMap<>();
        byReceiver.forEach((itemId, categories) -> {
            Map<Long, DemandChoices> categoryChoices = new HashMap<>();
            categories.forEach((categoryId, demands) -> categoryChoices.put(categoryId,
                new DemandChoices(List.copyOf(demands), demands.stream().map(PreparedDemand::id).toList())));
            choices.put(itemId, categoryChoices);
        });
        Edge[][] edges = new Edge[offers.size()][offers.size()];
        for (int from = 0; from < offers.size(); from++) {
            var provider = offers.get(from);
            Set<String> tags = normalized(provider.tags());
            for (int to = 0; to < offers.size(); to++) {
                var receiver = offers.get(to);
                if (provider.ownerId() == receiver.ownerId()) continue;
                DemandChoices matching = choices.getOrDefault(receiver.id(), Map.of()).get(provider.categoryId());
                if (matching != null) edges[from][to] = select(matching, tags);
            }
        }

        Results results = new Results(offers, edges);
        // A valid ring contains exactly one viewer-owned item. Anchor there, then normalize only its output ID.
        // This avoids generating unrelated rings and does not omit viewers whose item ID is not the minimum.
        for (int a = 0; a < offers.size(); a++) {
            if (offers.get(a).ownerId() != viewerId) continue;
            for (int b = 0; b < offers.size(); b++) {
                if (edges[a][b] == null) continue;
                if (edges[b][a] != null) results.add(a, b);
                for (int c = 0; c < offers.size(); c++) {
                    if (edges[b][c] != null && edges[c][a] != null) results.add(a, b, c);
                }
            }
        }
        return results.recommendations.values().stream().sorted(Comparator.comparingInt(Recommendation::score).reversed()
            .thenComparingInt(Recommendation::length).thenComparing(Recommendation::id)).toList();
    }

    private static Edge select(DemandChoices choices, Set<String> providerTags) {
        PreparedDemand selected = null;
        List<String> selectedTags = List.of();
        int bestContribution = -1;
        for (PreparedDemand demand : choices.demands()) {
            Set<String> shared = new TreeSet<>(providerTags);
            shared.retainAll(demand.preferredTags());
            int contribution = Math.min(shared.size(), 4);
            if (contribution > bestContribution || contribution == bestContribution && demand.id() < selected.id()) {
                selected = demand;
                selectedTags = List.copyOf(shared);
                bestContribution = contribution;
            }
        }
        return new Edge(selected.id(), choices.ids(), selectedTags, bestContribution, selected.version());
    }

    private static Set<String> normalized(Set<String> tags) {
        Set<String> result = new TreeSet<>();
        for (String tag : tags) if (tag != null && !tag.isBlank()) result.add(tag.trim().toLowerCase(Locale.ROOT));
        return Collections.unmodifiableSet(result);
    }

    /** Conflicting snapshots of the same ID are all excluded, before eligibility filtering. */
    private static <T> List<T> unambiguous(List<T> input, ToLongFunction<T> id) {
        Map<Long, T> byId = new TreeMap<>();
        Set<Long> conflicts = new HashSet<>();
        for (T value : input) {
            long key = id.applyAsLong(value);
            if (conflicts.contains(key)) continue;
            T previous = byId.putIfAbsent(key, value);
            if (previous != null && !previous.equals(value)) {
                byId.remove(key);
                conflicts.add(key);
            }
        }
        return List.copyOf(byId.values());
    }

    private record PreparedDemand(long id, Set<String> preferredTags, int version) {}
    private record DemandChoices(List<PreparedDemand> demands, List<Long> ids) {}
    private record Edge(long demandId, List<Long> matchedDemandIds, List<String> matchedTags, int contribution, int demandVersion) {}

    private static final class Results {
        private final List<IndependentMatchingInput.Offer> offers;
        private final Edge[][] edges;
        private final Map<String, Recommendation> recommendations = new HashMap<>();
        private long explanationDemandIds;

        private Results(List<IndependentMatchingInput.Offer> offers, Edge[][] edges) {
            this.offers = offers;
            this.edges = edges;
        }

        private void add(int... ring) {
            int minimum = 0;
            for (int i = 1; i < ring.length; i++)
                if (offers.get(ring[i]).id() < offers.get(ring[minimum]).id()) minimum = i;
            int[] canonical = new int[ring.length];
            List<String> itemIds = new ArrayList<>();
            long additionalIds = 0;
            for (int i = 0; i < ring.length; i++) {
                canonical[i] = ring[(minimum + i) % ring.length];
                itemIds.add(Long.toString(offers.get(canonical[i]).id()));
                additionalIds += edges[ring[i]][ring[(i + 1) % ring.length]].matchedDemandIds().size();
            }
            String id = RULE_VERSION + ":cycle-" + String.join("-", itemIds);
            if (recommendations.containsKey(id)) return;
            if (recommendations.size() >= MAX_RECOMMENDATIONS)
                throw new MatchingLimitException("独立需求匹配结果超过 1000 个方案；请先缩小候选范围");
            if (explanationDemandIds + additionalIds > MAX_EXPLANATION_DEMAND_IDS)
                throw new MatchingLimitException("独立需求匹配理由超过 20000 个需求引用；请先缩小候选范围");
            explanationDemandIds += additionalIds;

            List<Participant> participants = new ArrayList<>();
            List<Flow> flows = new ArrayList<>();
            int contribution = 0;
            for (int i = 0; i < canonical.length; i++) {
                int from = canonical[i], to = canonical[(i + 1) % canonical.length];
                var provider = offers.get(from);
                var receiver = offers.get(to);
                Edge edge = edges[from][to];
                contribution += edge.contribution();
                participants.add(new Participant(provider.ownerId(), provider.ownerName(), provider.id(), provider.title(), provider.version()));
                String reason = "满足" + receiver.ownerName() + "的独立需求 #" + edge.demandId() + "：「" +
                    provider.categoryName() + "」分类" + (edge.matchedTags().isEmpty() ?
                    "；分类匹配，暂无共同偏好标签" : "；偏好标签：" + String.join("、", edge.matchedTags()));
                flows.add(new Flow(provider.ownerId(), provider.ownerName(), receiver.ownerId(), receiver.ownerName(),
                    provider.id(), provider.title(), edge.demandId(), edge.matchedDemandIds(), provider.categoryId(),
                    provider.categoryName(), edge.matchedTags(), reason, edge.demandVersion()));
            }
            int score = 60 + (int) Math.round(10.0 * contribution / canonical.length);
            recommendations.put(id, new Recommendation(id, RULE_VERSION, canonical.length, score,
                List.copyOf(participants), List.copyOf(flows),
                "每人提供 1 件可交换物品，每名接收者至少一条与本人在本环提供物品关联的有效独立需求得到满足。每条流向选择标签计分贡献最高、同分需求 ID 最小的一条需求，标签计分贡献最多 4 个。标签仅用于排序，该推荐尚未占用物品。"));
        }
    }
}
