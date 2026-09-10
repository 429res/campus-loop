package edu.campusloop.web.exchange.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campusloop.common.ApiException;
import edu.campusloop.matching.IndependentMatchingInput;
import edu.campusloop.web.exchange.mapper.ExchangeCandidateMapper;
import edu.campusloop.web.matching.entity.MatchingDemandAssociation;
import edu.campusloop.web.matching.mapper.IndependentMatchingMapper;
import edu.campusloop.web.matching.service.IndependentMatchingSnapshotService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import java.util.stream.Collectors;

/** Read-only database adapter for domain verification. NOT an authorization ticket or the A-03 locked loader. */
@Service
public class ExchangeCandidateReader {
    private final ExchangeCandidateMapper items;
    private final IndependentMatchingMapper demands;
    private final ObjectMapper json;
    public ExchangeCandidateReader(ExchangeCandidateMapper items,IndependentMatchingMapper demands,ObjectMapper json) {
        this.items=items;this.demands=demands;this.json=json;
    }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public IndependentMatchingInput snapshot(List<Long> itemIds) { return snapshot(itemIds,true); }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public IndependentMatchingInput snapshot(List<Long> itemIds, boolean includeDemands) {
        if (itemIds==null || itemIds.size()<2 || itemIds.size()>3 || itemIds.stream().anyMatch(id -> id==null || id<1)
            || new HashSet<>(itemIds).size()!=itemIds.size()) throw new ApiException(400,"仅接受2或3个不同物品ID");
        var offers=items.items(itemIds).stream().map(i -> new IndependentMatchingInput.Offer(i.getId(),i.getOwnerId(),i.getOwnerName(),
            i.getTitle(),i.getCategoryId(),i.getCategoryName(),tags(i.getTagsJson()),i.getStatus(),i.getUserStatus(),i.isBlocked(),i.getVersion())).toList();
        if(!includeDemands)return new IndependentMatchingInput(offers,List.of());
        var rows=demands.activeAssociations(itemIds,IndependentMatchingSnapshotService.MAX_ACTIVE_ASSOCIATIONS+1);
        if (rows.size()>IndependentMatchingSnapshotService.MAX_ACTIVE_ASSOCIATIONS) throw new ApiException(422,"有效需求关联超过20000条");
        var grouped=rows.stream().collect(Collectors.groupingBy(MatchingDemandAssociation::getDemandId,TreeMap::new,Collectors.toList()));
        List<IndependentMatchingInput.Demand> values=new ArrayList<>();
        for(var group:grouped.values()) {
            var d=group.get(0);
            values.add(new IndependentMatchingInput.Demand(d.getDemandId(),d.getOwnerId(),d.getCategoryId(),tags(d.getPreferredTagsJson()),
                group.stream().map(MatchingDemandAssociation::getItemId).collect(Collectors.toSet()),d.getStatus(),d.getVersion()));
        }
        return new IndependentMatchingInput(offers,values);
    }
    private Set<String> tags(String value) {
        try { return new HashSet<>(json.readValue(value,new TypeReference<List<String>>(){})); }
        catch(Exception exception) { throw new IllegalStateException("Stored tags are invalid"); }
    }
}
