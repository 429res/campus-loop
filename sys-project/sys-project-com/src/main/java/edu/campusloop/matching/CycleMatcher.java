package edu.campusloop.matching;

import java.util.*;

/** Pure directed matching; reading recommendations never reserves an item. */
public final class CycleMatcher {
    public static final int MAX_ITEMS = 200;
    public static final int MAX_RECOMMENDATIONS = 1000;

    public record Offer(long id, long ownerId, String ownerName, String title, long categoryId,
                        String categoryName, long wantedCategoryId, Set<String> tags,
                        Set<String> wantedTags, String status) {
        public Offer {
            tags = normalized(tags); wantedTags = normalized(wantedTags);
        }
        private static Set<String> normalized(Set<String> tags) {
            if (tags == null) return Set.of();
            Set<String> result = new TreeSet<>();
            for (String tag : tags) if (tag != null && !tag.isBlank()) result.add(tag.trim().toLowerCase(Locale.ROOT));
            return Collections.unmodifiableSet(result);
        }
    }
    public record Participant(long userId, String displayName, long itemId, String itemTitle) {}
    public record Flow(long fromUserId, String fromName, long toUserId, String toName,
                       long itemId, String itemTitle, String reason) {}
    public record Recommendation(String id, int length, int score, List<Participant> participants,
                                 List<Flow> flows, String explanation) {}

    public List<Recommendation> find(List<Offer> input) {
        // Deduplicate conflicting input IDs conservatively; mapper normally guarantees PK uniqueness.
        Map<Long, List<Offer>> byId = new TreeMap<>();
        for (Offer offer : input) byId.computeIfAbsent(offer.id(), ignored -> new ArrayList<>()).add(offer);
        List<Offer> offers = byId.values().stream()
            .filter(group -> group.stream().distinct().count() == 1).map(group -> group.get(0))
            .filter(offer -> "AVAILABLE".equals(offer.status())).toList();
        if (offers.size() > MAX_ITEMS) {
            throw new MatchingLimitException("初版匹配支持最多 " + MAX_ITEMS + " 件可交换物品；请先缩小候选范围");
        }
        Map<String, Recommendation> result = new LinkedHashMap<>();
        for (Offer a : offers) for (Offer b : offers) {
            if (a.id() >= b.id() || !edge(a, b)) continue;
            if (edge(b, a)) add(result, List.of(a, b));
        }
        for (Offer a : offers) for (Offer b : offers) {
            if (a.id() >= b.id() || !edge(a, b)) continue;
            for (Offer c : offers) {
                if (a.id() >= c.id() || b.id() == c.id() || a.ownerId() == c.ownerId()) continue;
                if (edge(b, c) && edge(c, a)) add(result, List.of(a, b, c));
            }
        }
        return result.values().stream().sorted(Comparator.comparingInt(Recommendation::score).reversed()
            .thenComparingInt(Recommendation::length).thenComparing(Recommendation::id)).toList();
    }
    /** Hard rule: distinct owners/items and offered category exactly satisfies receiver's need. */
    private boolean edge(Offer provider, Offer receiver) {
        return provider.id() != receiver.id() && provider.ownerId() != receiver.ownerId()
            && provider.categoryId() == receiver.wantedCategoryId();
    }
    private void add(Map<String, Recommendation> result, List<Offer> ring) {
        String id = "cycle-" + String.join("-", ring.stream().map(o -> Long.toString(o.id())).toList());
        if (result.containsKey(id)) return;
        // Fail before constructing another explanation; never return a partial recommendation list.
        if (result.size() >= MAX_RECOMMENDATIONS) {
            throw new MatchingLimitException("匹配方案超过 " + MAX_RECOMMENDATIONS + " 条；请先缩小候选范围");
        }
        List<Flow> flows = new ArrayList<>(); int tagHits = 0;
        for (int i = 0; i < ring.size(); i++) {
            Offer provider = ring.get(i), receiver = ring.get((i + 1) % ring.size());
            Set<String> shared = new TreeSet<>(provider.tags()); shared.retainAll(receiver.wantedTags());
            tagHits += Math.min(shared.size(), 4);
            String reason = "满足" + receiver.ownerName() + "想要的「" + provider.categoryName() + "」分类"
                + (shared.isEmpty() ? "；分类匹配，暂无共同偏好标签" : "；偏好标签：" + String.join("、", shared));
            flows.add(new Flow(provider.ownerId(), provider.ownerName(), receiver.ownerId(), receiver.ownerName(),
                provider.id(), provider.title(), reason));
        }
        int score = 60 + (int) Math.round(10.0 * tagHits / ring.size());
        result.put(id, new Recommendation(id, ring.size(), score,
            ring.stream().map(o -> new Participant(o.ownerId(), o.ownerName(), o.id(), o.title())).toList(),
            List.copyOf(flows), "每人提供 1 件可交换物品，全部参与者的分类需求均满足。标签仅用于排序；该推荐尚未占用物品。"));
    }
}
