package edu.campusloop.web.review.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.campusloop.common.*;
import edu.campusloop.web.item.entity.Item;
import edu.campusloop.web.item.mapper.ItemMapper;
import edu.campusloop.web.review.entity.ItemReviewAudit;
import edu.campusloop.web.review.mapper.ItemReviewAuditMapper;
import edu.campusloop.web.review.vo.ItemReviewAuditView;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.user.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ItemReviewAuditService {
    private final ItemReviewAuditMapper audits;
    private final UserMapper users;
    private final ItemMapper items;
    private final ObjectMapper json;
    public ItemReviewAuditService(ItemReviewAuditMapper audits, UserMapper users, ItemMapper items, ObjectMapper json) {
        this.audits=audits; this.users=users; this.items=items; this.json=json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(long operatorId, String action, String reason, Item before, Item after) {
        User operator=users.selectById(operatorId);
        if(operator==null) throw new ApiException(401,"会话用户已不存在");
        ItemReviewAudit audit=new ItemReviewAudit();
        audit.setItemId(after.getId()); audit.setOperatorUserId(operatorId); audit.setOperatorDisplayName(operator.getDisplayName());
        audit.setAction(action); audit.setReason(reason);
        audit.setPreviousStatus(before==null?null:before.getStatus()); audit.setNewStatus(after.getStatus());
        audit.setPreviousVersion(before==null?null:before.getVersion()); audit.setNewVersion(after.getVersion());
        audit.setPreviousSnapshotJson(before==null?null:snapshot(before)); audit.setNewSnapshotJson(snapshot(after));
        audit.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
        audits.insert(audit);
    }

    public Map<Long,ItemReviewAudit> latestDecisions(List<Long> ids) {
        if(ids.isEmpty()) return Map.of();
        return audits.latestDecisions(ids).stream().collect(Collectors.toMap(ItemReviewAudit::getItemId,a->a));
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResult<ItemReviewAuditView> page(long itemId,int page,int size) {
        if(itemId<1 || page<1 || size<1 || size>100) throw new ApiException(400,"物品ID或分页参数不正确");
        if(items.selectById(itemId)==null) throw new ApiException(404,"物品不存在");
        Page<ItemReviewAudit> result=audits.selectPage(new Page<>(page,size),new QueryWrapper<ItemReviewAudit>()
            .eq("item_id",itemId).orderByDesc("new_version","id"));
        return new PageResult<>(result.getRecords().stream().map(a->new ItemReviewAuditView(a.getId(),a.getItemId(),
            a.getOperatorUserId(),a.getOperatorDisplayName(),a.getAction(),a.getReason(),a.getPreviousStatus(),a.getNewStatus(),
            a.getPreviousVersion(),a.getNewVersion(),parse(a.getPreviousSnapshotJson()),parse(a.getNewSnapshotJson()),a.getCreatedAt())).toList(),
            result.getTotal(),page,size);
    }

    private String snapshot(Item item) {
        // Explicit content whitelist: no credentials, session data, unrelated user fields or mutable joins.
        ObjectNode node=json.createObjectNode();
        node.put("id",item.getId()); node.put("ownerId",item.getOwnerId()); node.put("title",item.getTitle());
        node.put("description",item.getDescription()); node.put("categoryId",item.getCategoryId());
        node.put("conditionLevel",item.getConditionLevel()); node.set("tags",parse(item.getTagsJson()));
        node.put("wantedCategoryId",item.getWantedCategoryId()); node.set("wantedTags",parse(item.getWantedTagsJson()));
        node.put("imageUrl",item.getImageUrl()); node.put("status",item.getStatus()); node.put("version",item.getVersion());
        node.put("reviewBasis",item.getReviewBasis()); node.put("createdAt",item.getCreatedAt().toString()+"Z");
        return node.toString();
    }
    private JsonNode parse(String value) {
        if(value==null) return null;
        try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException("Stored item audit JSON is invalid");}
    }
}
