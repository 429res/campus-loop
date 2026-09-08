package edu.campusloop.matching;

import java.util.List;
import java.util.Set;

/** Immutable data read for independent-demand rules; it carries no persistence behavior. */
public record IndependentMatchingInput(List<Offer> offers, List<Demand> demands) {
    public IndependentMatchingInput {
        offers = List.copyOf(offers);
        demands = List.copyOf(demands);
    }

    public record Offer(long id, long ownerId, String ownerName, String title, long categoryId,
                        String categoryName, Set<String> tags, String status, String userStatus,
                        boolean held) {
        public Offer {
            tags = tags == null ? Set.of() : Set.copyOf(tags);
        }
    }

    public record Demand(long id, long ownerId, long categoryId, Set<String> preferredTags,
                         Set<Long> offeredItemIds, String status, int version) {
        public Demand {
            preferredTags = preferredTags == null ? Set.of() : Set.copyOf(preferredTags);
            offeredItemIds = offeredItemIds == null ? Set.of() : Set.copyOf(offeredItemIds);
        }
    }
}
