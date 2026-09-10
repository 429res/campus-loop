package edu.campusloop.web.demand.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import edu.campusloop.common.ApiException;
import edu.campusloop.web.demand.entity.Demand;
import edu.campusloop.web.demand.mapper.*;
import edu.campusloop.web.item.entity.Item;
import edu.campusloop.web.user.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;

@Service
@Transactional(propagation=Propagation.MANDATORY)
public class PublishedDemandService {
    private final DemandMapper demands; private final DemandItemMapper links; private final UserMapper users;
    public PublishedDemandService(DemandMapper demands,DemandItemMapper links,UserMapper users){this.demands=demands;this.links=links;this.users=users;}
    // Keep the same owner -> demand -> item order as exchange creation and demand edits.
    public void lock(long ownerId,Long itemId){
        var owner=users.selectByIdForUpdate(ownerId);
        if(owner==null||!"ACTIVE".equals(owner.getStatus()))throw new ApiException(403,"账号不可用");
        if(itemId!=null)demands.selectOne(new QueryWrapper<Demand>().eq("source_item_id",itemId).last("FOR UPDATE"));
    }
    public void sync(Item item){
        var current=demands.selectOne(new QueryWrapper<Demand>().eq("source_item_id",item.getId()));
        var now=LocalDateTime.now(ZoneOffset.UTC);
        String description="用「"+item.getTitle()+"」交换";
        if(current==null){
            var demand=new Demand();demand.setSourceItemId(item.getId());demand.setOwnerId(item.getOwnerId());
            demand.setCategoryId(item.getWantedCategoryId());demand.setDescription(description);demand.setPreferredTagsJson(item.getWantedTagsJson());
            demand.setStatus("ACTIVE");demand.setVersion(0);demand.setCreatedAt(now);demand.setUpdatedAt(now);
            demands.insert(demand);links.insert(demand.getId(),item.getId());
        }else{
            if(!current.getOwnerId().equals(item.getOwnerId())||demands.activeExchangeReferences(current.getId())>0)throw new ApiException(409,"物品正在交换，请先处理当前交换");
            if(current.getVersion()==Integer.MAX_VALUE)throw new ApiException(409,"需求版本已达到上限");
            demands.update(null,new UpdateWrapper<Demand>().eq("id",current.getId()).set("category_id",item.getWantedCategoryId()).set("description",description)
                .set("preferred_tags_json",item.getWantedTagsJson()).set("updated_at",now).setSql("version=version+1"));
        }
    }
}
