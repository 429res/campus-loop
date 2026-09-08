package edu.campusloop.web.category.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.campusloop.common.*;
import edu.campusloop.web.category.dto.*;
import edu.campusloop.web.category.entity.Category;
import edu.campusloop.web.category.mapper.CategoryMapper;
import edu.campusloop.web.category.vo.CategoryView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class CategoryService {
    private final CategoryMapper categories;
    public CategoryService(CategoryMapper categories) { this.categories = categories; }

    @Transactional(readOnly = true)
    public List<CategoryView> list(boolean includeInactive) {
        return categories.selectList(ordered().eq(!includeInactive, "status", "ACTIVE"))
            .stream().map(CategoryView::from).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<CategoryView> page(int page, int size, String keyword, String status) {
        if (page < 1 || size < 1 || size > 100 || (keyword != null && keyword.length() > 100))
            throw new ApiException(400, "分页或搜索参数不正确");
        if (status != null && !status.isEmpty() && !Set.of("ACTIVE", "INACTIVE").contains(status))
            throw new ApiException(400, "分类状态不正确");
        QueryWrapper<Category> query = ordered();
        if (keyword != null && !keyword.isBlank()) query.like("name_key", key(keyword));
        if (status != null && !status.isEmpty()) query.eq("status", status);
        Page<Category> result = categories.selectPage(new Page<>(page, size), query);
        return new PageResult<>(result.getRecords().stream().map(CategoryView::from).toList(), result.getTotal(), page, size);
    }

    @Transactional(readOnly = true)
    public CategoryView detail(long id) { return CategoryView.from(require(categories.selectById(id))); }

    public CategoryView create(CreateCategoryRequest request) {
        Category category = new Category();
        category.setName(request.name().trim()); category.setNameKey(key(request.name()));
        category.setStatus("ACTIVE"); category.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        category.setVersion(0);
        try { categories.insert(category); }
        catch (DuplicateKeyException e) { throw new ApiException(409, "分类名称已存在"); }
        return detail(category.getId());
    }

    public CategoryView patch(long id, PatchCategoryRequest request) {
        Category category = lockCurrent(id, request.version());
        if (category.getVersion() == Integer.MAX_VALUE) throw new ApiException(409, "分类版本已达到上限");
        UpdateWrapper<Category> update = new UpdateWrapper<Category>().eq("id", id).eq("version", request.version())
            .set("version", request.version() + 1);
        if (request.name() != null) update.set("name", request.name().trim()).set("name_key", key(request.name()));
        if (request.sortOrder() != null) update.set("sort_order", request.sortOrder());
        if (request.status() != null) update.set("status", request.status());
        try {
            if (categories.update(null, update) != 1) throw new ApiException(409, "分类已更新，请刷新后重试");
        } catch (DuplicateKeyException e) { throw new ApiException(409, "分类名称已存在"); }
        return detail(id);
    }

    public void delete(long id, int version) {
        lockCurrent(id, version);
        if (categories.referenceCount(id) > 0) throw new ApiException(409, "分类已被物品或需求引用，请使用停用");
        if (categories.delete(new QueryWrapper<Category>().eq("id", id).eq("version", version)) != 1)
            throw new ApiException(409, "分类已更新，请刷新后重试");
    }

    private Category lockCurrent(long id, int version) {
        Category category = require(categories.selectForUpdate(id));
        if (category.getVersion() != version) throw new ApiException(409, "分类已更新，请刷新后重试");
        return category;
    }
    private Category require(Category category) {
        if (category == null) throw new ApiException(404, "分类不存在");
        return category;
    }
    private QueryWrapper<Category> ordered() { return new QueryWrapper<Category>().orderByAsc("sort_order", "id"); }
    private String key(String name) { return name.trim().toLowerCase(Locale.ROOT); }
}
