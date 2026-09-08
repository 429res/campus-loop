package edu.campusloop.web.category.controller;

import edu.campusloop.common.*;
import edu.campusloop.web.category.dto.*;
import edu.campusloop.web.category.service.CategoryService;
import edu.campusloop.web.category.vo.CategoryView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/categories")
@Validated
public class AdminCategoryController {
    private final CategoryService categories;
    public AdminCategoryController(CategoryService categories) { this.categories = categories; }

    @GetMapping public ResultVo<PageResult<CategoryView>> page(@RequestParam(defaultValue="1") int page,
        @RequestParam(defaultValue="12") int size, @RequestParam(required=false) String keyword,
        @RequestParam(required=false) String status) {
        return ResultVo.success(categories.page(page, size, keyword, status));
    }
    @GetMapping("/{id}") public ResultVo<CategoryView> detail(@PathVariable @Positive long id) {
        return ResultVo.success(categories.detail(id));
    }
    @PostMapping public ResultVo<CategoryView> create(@Valid @RequestBody CreateCategoryRequest body) {
        return ResultVo.success(categories.create(body));
    }
    @PatchMapping("/{id}") public ResultVo<CategoryView> patch(@PathVariable @Positive long id,
        @Valid @RequestBody PatchCategoryRequest body) {
        return ResultVo.success(categories.patch(id, body));
    }
    @DeleteMapping("/{id}") public ResultVo<Void> delete(@PathVariable @Positive long id,
        @RequestParam(required=false) @NotNull @Min(0) Integer version) {
        categories.delete(id, version);
        return ResultVo.success(null);
    }
}
