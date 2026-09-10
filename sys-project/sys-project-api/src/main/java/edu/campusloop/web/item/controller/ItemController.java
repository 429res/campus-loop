package edu.campusloop.web.item.controller;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.*;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.item.service.ItemService;
import edu.campusloop.web.item.dto.*;
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
    @GetMapping("/mine") public ResultVo<PageResult<ItemView>> ownList(@RequestAttribute(AuthInterceptor.USER) User user,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int size,
        @RequestParam(required=false) String keyword,@RequestParam(required=false) Long categoryId,
        @RequestParam(required=false) String status){return ResultVo.success(items.ownPage(user.getId(),page,size,keyword,categoryId,status));}
    @GetMapping("/mine/{id}") public ResultVo<ItemView> ownDetail(@RequestAttribute(AuthInterceptor.USER) User user,
        @PathVariable long id){return ResultVo.success(items.ownDetail(user.getId(),id));}
    @PutMapping("/{id}") public ResultVo<ItemView> edit(@RequestAttribute(AuthInterceptor.USER) User user,
        @PathVariable long id,@Valid @RequestBody EditItemRequest body){return ResultVo.success(items.edit(user.getId(),id,body));}
    @PostMapping("/{id}/relist") public ResultVo<ItemView> relist(@RequestAttribute(AuthInterceptor.USER) User user,@PathVariable long id,@Valid @RequestBody WithdrawItemRequest body){return ResultVo.success(items.relist(user.getId(),id,body));}
    @PostMapping("/{id}/withdraw") public ResultVo<ItemView> withdraw(@RequestAttribute(AuthInterceptor.USER) User user,
        @PathVariable long id,@Valid @RequestBody WithdrawItemRequest body){return ResultVo.success(items.withdraw(user.getId(),id,body));}
}
