package edu.campusloop.web.admin.controller;
import edu.campusloop.common.*;
import edu.campusloop.web.item.service.ItemService;
import edu.campusloop.web.item.vo.ItemView;
import edu.campusloop.web.item.entity.Item;
import edu.campusloop.web.item.mapper.ItemMapper;
import edu.campusloop.web.user.mapper.UserMapper;
import edu.campusloop.web.matching.service.MatchingService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final ItemService items;private final ItemMapper itemMapper;private final UserMapper users;private final MatchingService matching;
    public AdminController(ItemService items,ItemMapper itemMapper,UserMapper users,MatchingService matching){this.items=items;this.itemMapper=itemMapper;this.users=users;this.matching=matching;}
    @GetMapping("/items") public ResultVo<PageResult<ItemView>> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int size,
        @RequestParam(required=false) String keyword,@RequestParam(required=false) Long categoryId){return ResultVo.success(items.page(page,size,keyword,categoryId,true));}
    @GetMapping("/stats") public ResultVo<Map<String,Long>> stats(){return ResultVo.success(Map.of("users",users.selectCount(null),"items",itemMapper.selectCount(null),
        "availableItems",itemMapper.selectCount(new QueryWrapper<Item>().eq("status","AVAILABLE")),"recommendations",(long)matching.recommendations().size()));}
}
