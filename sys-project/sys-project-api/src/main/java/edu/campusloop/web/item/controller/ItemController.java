package edu.campusloop.web.item.controller;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.*;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.item.service.ItemService;
import edu.campusloop.web.item.dto.PublishItemRequest;
import edu.campusloop.web.item.vo.ItemView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/items")
public class ItemController {
    private final ItemService items;
    public ItemController(ItemService items){this.items=items;}
    @GetMapping public ResultVo<PageResult<ItemView>> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int size,
        @RequestParam(required=false) String keyword,@RequestParam(required=false) Long categoryId){return ResultVo.success(items.page(page,size,keyword,categoryId,false));}
    @GetMapping("/{id}") public ResultVo<ItemView> detail(@PathVariable long id){return ResultVo.success(items.detail(id));}
    @PostMapping public ResultVo<ItemView> publish(@RequestAttribute(AuthInterceptor.USER) User user,@Valid @RequestBody PublishItemRequest body){return ResultVo.success(items.publish(user.getId(),body));}
}
