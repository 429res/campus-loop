package edu.campusloop.web.category.controller;
import edu.campusloop.common.ResultVo;
import edu.campusloop.web.category.service.CategoryService;
import edu.campusloop.web.category.vo.CategoryView;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
public class CategoryController {
    private final CategoryService categories;
    public CategoryController(CategoryService categories){this.categories=categories;}
    @GetMapping("/api/categories") public ResultVo<List<CategoryView>> list(@RequestParam(defaultValue="false") boolean includeInactive){
        return ResultVo.success(categories.list(includeInactive));
    }
}
