package edu.campusloop.web.matching.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campusloop.common.ApiException;
import edu.campusloop.matching.IndependentMatchingInput;
import edu.campusloop.web.item.service.ItemService;
import edu.campusloop.web.matching.entity.MatchingDemandAssociation;
import edu.campusloop.web.matching.mapper.IndependentMatchingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class IndependentMatchingSnapshotService {
    public static final int MAX_ACTIVE_ASSOCIATIONS = 20_000;

    private final ItemService items;
    private final IndependentMatchingMapper demands;
    private final ObjectMapper json;

    public IndependentMatchingSnapshotService(ItemService items, IndependentMatchingMapper demands,
                                              ObjectMapper json) {
        this.items = items;
        this.demands = demands;
        this.json = json;
    }

    /** Both reads share a snapshot; no locks, reservations, or exchange writes are performed. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IndependentMatchingInput snapshot() {
        // Keep the existing 200-item guard before filtering by independent demands.
        List<IndependentMatchingInput.Offer> offers = items.availableForMatching().stream()
            .map(item -> new IndependentMatchingInput.Offer(item.id(), item.ownerId(), item.ownerName(),
                item.title(), item.categoryId(), item.categoryName(), new LinkedHashSet<>(item.tags()),
                item.status(), "ACTIVE", false, item.version()))
            .toList();
        if (offers.isEmpty()) return new IndependentMatchingInput(List.of(), List.of());

        List<MatchingDemandAssociation> rows = demands.activeAssociations(
            offers.stream().map(IndependentMatchingInput.Offer::id).toList(), MAX_ACTIVE_ASSOCIATIONS + 1);
        if (rows.size() > MAX_ACTIVE_ASSOCIATIONS) {
            throw new ApiException(422, "独立需求匹配支持最多 20000 条有效物品关联；请先实现候选分区");
        }

        Map<Long, List<MatchingDemandAssociation>> grouped = new LinkedHashMap<>();
        for (MatchingDemandAssociation row : rows) {
            grouped.computeIfAbsent(row.getDemandId(), ignored -> new ArrayList<>()).add(row);
        }
        List<IndependentMatchingInput.Demand> activeDemands = new ArrayList<>();
        for (List<MatchingDemandAssociation> group : grouped.values()) {
            MatchingDemandAssociation first = group.get(0);
            Set<Long> offeredItemIds = new LinkedHashSet<>();
            for (MatchingDemandAssociation row : group) offeredItemIds.add(row.getItemId());
            activeDemands.add(new IndependentMatchingInput.Demand(first.getDemandId(), first.getOwnerId(),
                first.getCategoryId(), decode(first.getPreferredTagsJson()), offeredItemIds,
                first.getStatus(), first.getVersion()));
        }
        return new IndependentMatchingInput(offers, activeDemands);
    }

    private Set<String> decode(String tags) {
        try {
            return new LinkedHashSet<>(json.readValue(tags, new TypeReference<List<String>>() {}));
        } catch (Exception exception) {
            throw new IllegalStateException("Stored demand tags are invalid");
        }
    }
}
