package edu.campusloop.web.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import edu.campusloop.common.ApiException;
import edu.campusloop.web.admin.vo.AdminStatsView;
import edu.campusloop.web.item.entity.Item;
import edu.campusloop.web.item.mapper.ItemMapper;
import edu.campusloop.web.matching.service.MatchingService;
import edu.campusloop.web.user.mapper.UserMapper;
import org.springframework.stereotype.Service;
import static edu.campusloop.web.admin.vo.AdminStatsView.RecommendationStatus.*;

@Service
public class AdminStatsService {
    private final UserMapper users;
    private final ItemMapper items;
    private final MatchingService matching;

    public AdminStatsService(UserMapper users, ItemMapper items, MatchingService matching) {
        this.users = users;
        this.items = items;
        this.matching = matching;
    }

    public AdminStatsView stats() {
        long userCount = users.selectCount(null), itemCount = items.selectCount(null);
        long available = items.selectCount(new QueryWrapper<Item>().eq("status", "AVAILABLE"));
        try {
            return new AdminStatsView(userCount, itemCount, available,
                (long) matching.recommendations().size(), AVAILABLE);
        } catch (ApiException error) {
            if (error.getStatus() != 422) throw error;
            return new AdminStatsView(userCount, itemCount, available, null, LIMIT_EXCEEDED);
        }
    }
}
