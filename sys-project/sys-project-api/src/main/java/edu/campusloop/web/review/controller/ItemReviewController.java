package edu.campusloop.web.review.controller;

import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.*;
import edu.campusloop.web.item.vo.ItemView;
import edu.campusloop.web.review.dto.ReviewDecisionRequest;
import edu.campusloop.web.review.service.*;
import edu.campusloop.web.review.vo.ItemReviewAuditView;
import edu.campusloop.web.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/items/{id}")
public class ItemReviewController {
    private final ItemReviewService reviews;
    private final ItemReviewAuditService audits;
    public ItemReviewController(ItemReviewService reviews,ItemReviewAuditService audits){this.reviews=reviews;this.audits=audits;}
    @PostMapping("/review") public ResultVo<ItemView> decide(@PathVariable long id,
        @RequestAttribute(AuthInterceptor.USER) User operator,@Valid @RequestBody ReviewDecisionRequest body) {
        return ResultVo.success(reviews.decide(operator.getId(),id,body));
    }
    @GetMapping("/review-audits") public ResultVo<PageResult<ItemReviewAuditView>> audits(@PathVariable long id,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int size) {
        return ResultVo.success(audits.page(id,page,size));
    }
}
