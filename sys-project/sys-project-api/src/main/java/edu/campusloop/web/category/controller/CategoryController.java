package edu.campusloop.web.category.controller;
import edu.campusloop.common.ResultVo;
import edu.campusloop.web.category.entity.Category;
import edu.campusloop.web.category.mapper.CategoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
public class CategoryController {
    private final CategoryMapper categories;
    public CategoryController(CategoryMapper categories){this.categories=categories;}
    @GetMapping("/api/categories") public ResultVo<List<Category>> list(){return ResultVo.success(categories.selectList(new QueryWrapper<Category>().orderByAsc("id")));}
}
