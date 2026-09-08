package edu.campusloop.web.favorite.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.campusloop.common.*;
import edu.campusloop.web.favorite.entity.Favorite;
import edu.campusloop.web.favorite.mapper.FavoriteMapper;
import edu.campusloop.web.favorite.vo.*;
import edu.campusloop.web.item.service.ItemService;
import edu.campusloop.web.item.vo.ItemView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FavoriteService {
    private final FavoriteMapper favorites;
    private final ItemService items;

    public FavoriteService(FavoriteMapper favorites, ItemService items) {
        this.favorites = favorites;
        this.items = items;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FavoriteStatus add(long userId, long itemId) {
        requireItemId(itemId);
        if (favorites.lockItem(itemId) == null) throw new ApiException(404, "物品不存在或暂不可见");
        // Always recheck visibility, even for an existing bookmark. The item cannot change under this lock.
        items.detail(itemId);
        if (favorites.selectCount(owned(userId, itemId)) == 0) {
            Favorite favorite = new Favorite();
            favorite.setUserId(userId);
            favorite.setItemId(itemId);
            favorite.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
            favorites.insert(favorite);
        }
        return new FavoriteStatus(itemId, true);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FavoriteStatus remove(long userId, long itemId) {
        requireItemId(itemId);
        favorites.lockItem(itemId);
        // Removing one's own relation never reveals whether the target still exists or is visible.
        favorites.delete(owned(userId, itemId));
        return new FavoriteStatus(itemId, false);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<FavoriteView> page(long userId, int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new ApiException(400, "分页参数不正确");
        Page<Favorite> result = favorites.selectPage(new Page<>(page, size),
            new QueryWrapper<Favorite>().eq("user_id", userId).orderByDesc("created_at", "id"));
        Map<Long, ItemView> visible = items.visibleDetails(result.getRecords().stream().map(Favorite::getItemId).toList())
            .stream().collect(Collectors.toMap(ItemView::id, Function.identity()));
        return new PageResult<>(result.getRecords().stream().map(favorite -> new FavoriteView(
            favorite.getItemId(), favorite.getCreatedAt(), visible.containsKey(favorite.getItemId()),
            visible.get(favorite.getItemId()))).toList(), result.getTotal(), page, size);
    }

    private QueryWrapper<Favorite> owned(long userId, long itemId) {
        return new QueryWrapper<Favorite>().eq("user_id", userId).eq("item_id", itemId);
    }

    private void requireItemId(long itemId) {
        if (itemId < 1) throw new ApiException(400, "物品 ID 须为正整数");
    }
}
