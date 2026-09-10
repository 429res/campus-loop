package edu.campusloop.web.review.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import edu.campusloop.common.ApiException;
import edu.campusloop.web.item.entity.Item;
import edu.campusloop.web.item.mapper.ItemMapper;
import edu.campusloop.web.item.service.*;
import edu.campusloop.web.item.vo.ItemView;
import edu.campusloop.web.review.dto.ReviewDecisionRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ItemReviewService {
    private final ItemMapper items;
    private final edu.campusloop.web.user.mapper.UserMapper users;
    private final ItemMutationGuard guard;
    private final ItemReviewAuditService audits;
    private final ItemService views;
    public ItemReviewService(ItemMapper items, ItemMutationGuard guard, ItemReviewAuditService audits, ItemService views, edu.campusloop.web.user.mapper.UserMapper users) {
        this.items=items;this.users=users; this.guard=guard; this.audits=audits; this.views=views;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public ItemView decide(long operatorId,long id,ReviewDecisionRequest request) {
        if(id<1) throw new ApiException(400,"物品ID不正确");
        Item hint=items.selectById(id);
        if(hint==null)throw new ApiException(404,"物品不存在");
        // Notification and audit foreign keys also touch users. Lock them before the item.
        for(long userId:new java.util.TreeSet<>(java.util.List.of(operatorId,hint.getOwnerId()))){
            var user=users.selectByIdForUpdate(userId);
            if(userId==operatorId){if(user==null)throw new ApiException(401,"账号不可用");edu.campusloop.auth.AdminPermissions.require(user,"ITEMS");}
        }
        Item before=items.selectForUpdate(id);
        if(before==null) throw new ApiException(404,"物品不存在");
        if(!hint.getOwnerId().equals(before.getOwnerId()))throw new ApiException(409,"物品归属已变化，请刷新后重试");
        guard.requireVersion(before,request.version());
        if(!"PENDING_REVIEW".equals(before.getStatus())) throw new ApiException(409,"只有待审核物品可以作出审核决定");
        guard.requireUnoccupied(before);
        String status="APPROVE".equals(request.decision())?"AVAILABLE":"REJECTED";
        if(items.update(null,new UpdateWrapper<Item>().eq("id",id).eq("status","PENDING_REVIEW").eq("version",request.version())
            .set("status",status).set("review_basis","APPROVE".equals(request.decision())?(Boolean.TRUE.equals(users.selectById(operatorId).getSystemAccount())?"AI_REVIEW":"ADMIN_REVIEW"):"UNREVIEWED")
            .set("version",request.version()+1))!=1) throw new ApiException(409,"物品已更新，请刷新后重试");
        Item after=items.selectById(id);
        audits.append(operatorId,request.decision(),request.reason(),before,after);
        return views.adminDetail(id);
    }
}
