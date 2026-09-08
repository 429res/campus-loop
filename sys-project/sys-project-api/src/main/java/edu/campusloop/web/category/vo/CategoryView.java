package edu.campusloop.web.category.vo;

import edu.campusloop.web.category.entity.Category;

public record CategoryView(Long id, String name, String status, Integer sortOrder, Integer version) {
    public static CategoryView from(Category category) {
        return new CategoryView(category.getId(), category.getName(), category.getStatus(),
            category.getSortOrder(), category.getVersion());
    }
}
