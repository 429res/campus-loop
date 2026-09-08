package edu.campusloop.web.demand.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campusloop.common.ApiException;
import edu.campusloop.common.PageResult;
import edu.campusloop.web.category.entity.Category;
import edu.campusloop.web.category.mapper.CategoryMapper;
import edu.campusloop.web.category.service.CategorySelectionService;
import edu.campusloop.web.demand.dto.*;
import edu.campusloop.web.demand.entity.Demand;
import edu.campusloop.web.demand.entity.DemandItem;
import edu.campusloop.web.demand.mapper.DemandItemMapper;
import edu.campusloop.web.demand.mapper.DemandMapper;
import edu.campusloop.web.demand.service.DemandService;
import edu.campusloop.web.demand.vo.*;
import edu.campusloop.web.item.entity.Item;
import edu.campusloop.web.item.mapper.ItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
// Current reads after row locking must not reuse an earlier repeatable-read item snapshot.
@Transactional(isolation = Isolation.READ_COMMITTED)
public class DemandServiceImpl extends ServiceImpl<DemandMapper, Demand> implements DemandService {
    private final CategoryMapper categories;
    private final ItemMapper items;
    private final DemandItemMapper associations;
    private final ObjectMapper json;
    private final CategorySelectionService categorySelection;

    public DemandServiceImpl(CategoryMapper categories, ItemMapper items, DemandItemMapper associations,
                             ObjectMapper json, CategorySelectionService categorySelection) {
        this.categories = categories;
        this.items = items;
        this.associations = associations;
        this.json = json;
        this.categorySelection = categorySelection;
    }

    @Override
    public DemandView create(long ownerId, CreateDemandRequest request) {
        List<Long> offeredIds = validateAndLockItems(ownerId, request.offeredItemIds());
        categorySelection.requireActive(Collections.singletonList(request.categoryId()));
        LocalDateTime now = now();
        Demand demand = new Demand();
        demand.setOwnerId(ownerId);
        demand.setCategoryId(request.categoryId());
        demand.setDescription(request.description());
        demand.setPreferredTagsJson(encode(request.preferredTags()));
        demand.setStatus("ACTIVE");
        demand.setVersion(0);
        demand.setCreatedAt(now);
        demand.setUpdatedAt(now);
        baseMapper.insert(demand);
        replaceItems(demand.getId(), offeredIds);
        return views(List.of(demand)).get(0);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<DemandView> page(long ownerId, int page, int size, String status) {
        requirePage(page, size);
        if (status != null && !status.isEmpty()) requireStatus(status);
        QueryWrapper<Demand> query = new QueryWrapper<Demand>().eq("owner_id", ownerId).ne("status", "DELETED");
        if (status != null && !status.isEmpty()) query.eq("status", status);
        query.orderByDesc("created_at", "id");
        Page<Demand> result = baseMapper.selectPage(new Page<>(page, size), query);
        return new PageResult<>(views(result.getRecords()), result.getTotal(), page, size);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DemandView detail(long ownerId, long id) {
        return views(List.of(requireOwned(baseMapper.selectById(id), ownerId))).get(0);
    }

    @Override
    public DemandView patch(long ownerId, long id, PatchDemandRequest request) {
        Demand demand = lockCurrent(ownerId, id, request.version());
        if (!request.isUpdatePresent()) throw new ApiException(400, "至少提交一个可编辑字段");
        UpdateWrapper<Demand> update = new UpdateWrapper<>();
        if (request.categoryId() != null) {
            demand.setCategoryId(request.categoryId());
            update.set("category_id", request.categoryId());
        }
        if (request.description() != null) {
            demand.setDescription(request.description());
            update.set("description", request.description());
        }
        if (request.preferredTags() != null) {
            demand.setPreferredTagsJson(encode(request.preferredTags()));
            update.set("preferred_tags_json", demand.getPreferredTagsJson());
        }
        // Omitting this field preserves historical associations without revalidating them.
        List<Long> offeredIds = request.offeredItemIds() == null ? null :
            validateAndLockItems(ownerId, request.offeredItemIds());
        categorySelection.requireActive(Collections.singletonList(demand.getCategoryId()));
        advance(demand, update);
        if (offeredIds != null) replaceItems(id, offeredIds);
        return views(List.of(demand)).get(0);
    }

    @Override
    public DemandView changeStatus(long ownerId, long id, DemandStatusRequest request) {
        Demand demand = lockCurrent(ownerId, id, request.version());
        requireStatus(request.status());
        if ("ACTIVE".equals(request.status())) {
            validateAndLockItems(ownerId, associations.itemIds(id));
            categorySelection.requireActive(Collections.singletonList(demand.getCategoryId()));
        }
        demand.setStatus(request.status());
        advance(demand, new UpdateWrapper<Demand>().set("status", request.status()));
        return views(List.of(demand)).get(0);
    }

    @Override
    public DeletedDemandView delete(long ownerId, long id, Integer version) {
        Demand demand = lockCurrent(ownerId, id, version);
        // Keep payload, ID and associations for current and future persistent references.
        demand.setStatus("DELETED");
        advance(demand, new UpdateWrapper<Demand>().set("status", "DELETED"));
        return new DeletedDemandView(id, demand.getStatus(), demand.getVersion());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<OfferedItemView> offerableItems(long ownerId, int page, int size) {
        requirePage(page, size);
        QueryWrapper<Item> query = new QueryWrapper<Item>().eq("owner_id", ownerId).eq("status", "AVAILABLE")
            .notExists("SELECT 1 FROM cl_item_hold h WHERE h.item_id = cl_item.id")
            .orderByDesc("created_at", "id");
        Page<Item> result = items.selectPage(new Page<>(page, size), query);
        return new PageResult<>(result.getRecords().stream().map(item -> offeredView(ownerId, item, false)).toList(),
            result.getTotal(), page, size);
    }

    private Demand lockCurrent(long ownerId, long id, Integer version) {
        if (version == null || version < 0) throw new ApiException(400, "需要非负整数 version");
        Demand demand = requireOwned(baseMapper.selectForUpdate(id), ownerId);
        if (!version.equals(demand.getVersion())) throw new ApiException(409, "需求已更新，请刷新后重试");
        if (version == Integer.MAX_VALUE) throw new ApiException(409, "需求版本已达到上限");
        return demand;
    }

    private Demand requireOwned(Demand demand, long ownerId) {
        if (demand == null || "DELETED".equals(demand.getStatus())) throw new ApiException(404, "需求不存在");
        if (demand.getOwnerId() != ownerId) throw new ApiException(403, "只能访问本人需求");
        return demand;
    }

    private void advance(Demand demand, UpdateWrapper<Demand> update) {
        int previousVersion = demand.getVersion();
        demand.setVersion(previousVersion + 1);
        demand.setUpdatedAt(now());
        update.eq("id", demand.getId()).eq("owner_id", demand.getOwnerId()).eq("version", previousVersion)
            .ne("status", "DELETED").set("version", demand.getVersion()).set("updated_at", demand.getUpdatedAt());
        if (baseMapper.update(null, update) != 1) throw new ApiException(409, "需求已更新，请刷新后重试");
    }

    private List<Long> validateAndLockItems(long ownerId, List<Long> offeredIds) {
        if (offeredIds == null || offeredIds.size() > 100 ||
            offeredIds.stream().anyMatch(id -> id == null || id < 1) || new HashSet<>(offeredIds).size() != offeredIds.size()) {
            throw new ApiException(400, "可提供物品须为最多 100 个不同的正整数 ID");
        }
        List<Long> sorted = offeredIds.stream().sorted().toList();
        for (Long id : sorted) {
            Item item = associations.lockItem(id);
            if (item == null) throw new ApiException(400, "关联物品不存在");
            if (item.getOwnerId() != ownerId) throw new ApiException(403, "只能关联本人拥有的物品");
            if (!"AVAILABLE".equals(item.getStatus()) || associations.lockHold(id) != null)
                throw new ApiException(409, "关联物品当前不可提供");
        }
        return sorted;
    }

    private void replaceItems(long demandId, List<Long> itemIds) {
        associations.deleteForDemand(demandId);
        for (Long itemId : itemIds) associations.insert(demandId, itemId);
    }

    private List<DemandView> views(List<Demand> demands) {
        if (demands.isEmpty()) return List.of();
        Map<Long, String> categoryNames = categories.selectBatchIds(demands.stream().map(Demand::getCategoryId).distinct().toList())
            .stream().collect(Collectors.toMap(Category::getId, Category::getName));
        List<DemandItem> links = associations.associations(demands.stream().map(Demand::getId).toList());
        List<Long> itemIds = links.stream().map(DemandItem::getItemId).distinct().toList();
        Map<Long, Item> linkedItems = itemIds.isEmpty() ? Map.of() : items.selectBatchIds(itemIds).stream()
            .collect(Collectors.toMap(Item::getId, item -> item));
        Set<Long> held = itemIds.isEmpty() ? Set.of() : new HashSet<>(associations.heldItemIds(itemIds));
        Map<Long, List<DemandItem>> byDemand = links.stream().collect(Collectors.groupingBy(DemandItem::getDemandId));
        return demands.stream().map(demand -> {
            List<OfferedItemView> offered = byDemand.getOrDefault(demand.getId(), List.of()).stream().map(link -> {
                Item item = linkedItems.get(link.getItemId());
                if (item == null) return new OfferedItemView(link.getItemId(), null, null, null, null, false);
                return offeredView(demand.getOwnerId(), item, held.contains(item.getId()));
            }).toList();
            return new DemandView(demand.getId(), demand.getOwnerId(), demand.getCategoryId(),
                categoryNames.get(demand.getCategoryId()), demand.getDescription(), decode(demand.getPreferredTagsJson()),
                demand.getStatus(), demand.getVersion(), demand.getCreatedAt(), demand.getUpdatedAt(), offered);
        }).toList();
    }

    private OfferedItemView offeredView(long ownerId, Item item, boolean held) {
        if (item.getOwnerId() != ownerId) return new OfferedItemView(item.getId(), null, null, null, null, false);
        return new OfferedItemView(item.getId(), item.getTitle(), item.getCategoryId(), item.getConditionLevel(),
            item.getStatus(), "AVAILABLE".equals(item.getStatus()) && !held);
    }

    private void requireStatus(String status) {
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) throw new ApiException(400, "需求状态不正确");
    }

    private void requirePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new ApiException(400, "分页参数不正确");
    }

    private String encode(List<String> tags) {
        try {
            return json.writeValueAsString(tags.stream().map(String::trim).map(tag -> tag.toLowerCase(Locale.ROOT)).distinct().toList());
        } catch (Exception e) {
            throw new ApiException(400, "标签格式不正确");
        }
    }

    private List<String> decode(String tags) {
        try {
            return json.readValue(tags, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Stored demand tags are invalid");
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }
}
