package edu.campusloop.web.matching.service;
import edu.campusloop.common.ApiException;
import edu.campusloop.matching.CycleMatcher;
import edu.campusloop.matching.MatchingLimitException;
import edu.campusloop.web.item.service.ItemService;
import org.springframework.stereotype.Service;
import java.util.*;
@Service
public class MatchingService {
    private final ItemService items; private final CycleMatcher matcher=new CycleMatcher();
    public MatchingService(ItemService items){this.items=items;}
    public List<CycleMatcher.Recommendation> recommendations(){
        try {
            return matcher.find(items.availableForMatching().stream().map(i->new CycleMatcher.Offer(i.id(),i.ownerId(),i.ownerName(),i.title(),i.categoryId(),
                i.categoryName(),i.wantedCategoryId(),new HashSet<>(i.tags()),new HashSet<>(i.wantedTags()),i.status())).toList());
        } catch (MatchingLimitException limit) {
            throw new ApiException(422, limit.getMessage());
        }
    }
}
