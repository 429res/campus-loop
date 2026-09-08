package edu.campusloop.web.category.service;

import edu.campusloop.common.ApiException;
import edu.campusloop.web.category.entity.Category;
import edu.campusloop.web.category.mapper.CategoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Collection;

@Service
public class CategorySelectionService {
    private final CategoryMapper categories;
    public CategorySelectionService(CategoryMapper categories) { this.categories = categories; }

    /** Existing item locks come first; lock distinct categories in ascending order until commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireActive(Collection<Long> ids) {
        if (ids.stream().anyMatch(id -> id == null || id < 1)) throw new ApiException(400, "分类不存在");
        for (long id : ids.stream().distinct().sorted().toList()) {
            Category category = categories.selectForUpdate(id);
            if (category == null) throw new ApiException(400, "分类不存在");
            if (!"ACTIVE".equals(category.getStatus())) throw new ApiException(400, "分类已停用，请选择可用分类");
        }
    }
}
