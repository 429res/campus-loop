package edu.campusloop.web.favorite.controller;

import com.fasterxml.jackson.databind.JsonNode;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.*;
import edu.campusloop.web.favorite.service.FavoriteService;
import edu.campusloop.web.favorite.vo.*;
import edu.campusloop.web.user.entity.User;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class FavoriteController {
    private final FavoriteService favorites;

    public FavoriteController(FavoriteService favorites) { this.favorites = favorites; }

    @PutMapping("/items/{id}/favorite")
    public ResultVo<FavoriteStatus> add(@RequestAttribute(AuthInterceptor.USER) User user, @PathVariable long id,
        @RequestParam MultiValueMap<String, String> query, @RequestBody(required = false) JsonNode body) {
        requireEmpty(query, body);
        return ResultVo.success(favorites.add(user.getId(), id));
    }

    @DeleteMapping("/items/{id}/favorite")
    public ResultVo<FavoriteStatus> remove(@RequestAttribute(AuthInterceptor.USER) User user, @PathVariable long id,
        @RequestParam MultiValueMap<String, String> query, @RequestBody(required = false) JsonNode body) {
        requireEmpty(query, body);
        return ResultVo.success(favorites.remove(user.getId(), id));
    }

    @GetMapping("/favorites")
    public ResultVo<PageResult<FavoriteView>> list(@RequestAttribute(AuthInterceptor.USER) User user,
        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "12") int size,
        @RequestParam MultiValueMap<String, String> query) {
        if (query.entrySet().stream().anyMatch(entry -> !Set.of("page", "size").contains(entry.getKey()) || entry.getValue().size() != 1))
            throw new ApiException(400, "收藏列表仅接受单个 page 和 size 参数，身份由会话确定");
        return ResultVo.success(favorites.page(user.getId(), page, size));
    }

    private void requireEmpty(MultiValueMap<String, String> query, JsonNode body) {
        if (!query.isEmpty() || (body != null && (!body.isObject() || !body.isEmpty())))
            throw new ApiException(400, "收藏操作不接受查询参数或请求字段，身份由会话确定");
    }
}
