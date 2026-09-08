package edu.campusloop.web.item.service.impl;
import edu.campusloop.common.*;
import edu.campusloop.web.item.service.ItemService;
import edu.campusloop.web.review.service.ItemReviewAuditService;
import edu.campusloop.web.item.service.ItemVisibility;
import edu.campusloop.web.item.service.ItemMutationGuard;
import edu.campusloop.web.item.entity.Item;
import edu.campusloop.web.item.mapper.ItemMapper;
import edu.campusloop.web.item.dto.*;
import edu.campusloop.web.item.vo.ItemView;
import edu.campusloop.web.category.entity.Category;
import edu.campusloop.web.category.mapper.CategoryMapper;
import edu.campusloop.web.category.service.CategorySelectionService;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.user.mapper.UserMapper;

import edu.campusloop.web.upload.service.UploadReferenceService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
@Service
public class ItemServiceImpl extends ServiceImpl<ItemMapper,Item> implements ItemService {
    private static final Set<String> OWN_STATES=Set.of("DRAFT","PENDING_REVIEW","REJECTED","AVAILABLE","RESERVED","EXCHANGED","HIDDEN");
    private final CategoryMapper categories;private final UserMapper users;private final UploadReferenceService uploads;private final ObjectMapper json;
    private final CategorySelectionService categorySelection;
    private final ItemMutationGuard mutationGuard;
    private final ItemReviewAuditService audits;
    public ItemServiceImpl(CategoryMapper categories,UserMapper users,UploadReferenceService uploads,ObjectMapper json,CategorySelectionService categorySelection,ItemMutationGuard mutationGuard,ItemReviewAuditService audits) {
        this.categories=categories;this.users=users;this.uploads=uploads;this.json=json;this.categorySelection=categorySelection;
        this.mutationGuard=mutationGuard;this.audits=audits;
    }
    @Override public PageResult<ItemView> page(int page,int size,String keyword,Long categoryId,boolean admin) {
        return queryPage(page,size,keyword,categoryId,admin,null);
    }
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResult<ItemView> adminPage(int page,int size,String keyword,Long categoryId,String status) {
        if(status!=null && !status.isEmpty() && !OWN_STATES.contains(status)) throw new ApiException(400,"物品状态不正确");
        return queryPage(page,size,keyword,categoryId,true,status);
    }
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ItemView adminDetail(long id) {
        if(id<1) throw new ApiException(400,"物品ID不正确");
        Item item=baseMapper.selectById(id);
        if(item==null) throw new ApiException(404,"物品不存在");
        return views(List.of(item),true).get(0);
    }
    private PageResult<ItemView> queryPage(int page,int size,String keyword,Long categoryId,boolean admin,String status) {
        if(page<1 || size<1 || size>100 || (keyword!=null && keyword.length()>100) || (categoryId!=null && categoryId<1)) throw new ApiException(400,"分页或搜索参数不正确");
        QueryWrapper<Item> query=new QueryWrapper<>();
        if(!admin) query.in("status",ItemVisibility.PUBLIC_STATES);
        if(status!=null && !status.isEmpty()) query.eq("status",status);
        if(keyword!=null && !keyword.isBlank()) query.like("title",keyword.trim());
        if(categoryId!=null) query.eq("category_id",categoryId);
        query.orderByDesc("created_at","id");
        Page<Item> result=baseMapper.selectPage(new Page<>(page,size),query);
        return new PageResult<>(views(result.getRecords(),admin),result.getTotal(),page,size);
    }
    @Override public ItemView detail(long id) {
        Item item=baseMapper.selectById(id);
        if(item==null || !ItemVisibility.PUBLIC_STATES.contains(item.getStatus())) throw new ApiException(404,"物品不存在或暂不可见");
        return views(List.of(item)).get(0);
    }
    @Override public List<ItemView> visibleDetails(List<Long> ids) {
        if(ids.isEmpty()) return List.of();
        return views(baseMapper.selectList(new QueryWrapper<Item>().in("id",ids).in("status",ItemVisibility.PUBLIC_STATES)));
    }
    @Override @Transactional public ItemView publish(long ownerId,PublishItemRequest request) {
        String image=validateFields(ownerId,request);
        Item item=new Item();item.setOwnerId(ownerId);item.setTitle(request.title().trim());item.setDescription(request.description().trim());
        item.setCategoryId(request.categoryId());item.setConditionLevel(request.conditionLevel());item.setTagsJson(encode(request.tags()));
        item.setWantedCategoryId(request.wantedCategoryId());item.setWantedTagsJson(encode(request.wantedTags()));
        item.setImageUrl(image);item.setStatus("PENDING_REVIEW");item.setReviewBasis("UNREVIEWED");item.setVersion(0);item.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        baseMapper.insert(item);item=baseMapper.selectById(item.getId());
        audits.append(ownerId,"SUBMIT",null,null,item);return views(List.of(item),true).get(0);
    }
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResult<ItemView> ownPage(long ownerId,int page,int size,String keyword,Long categoryId,String status) {
        if(page<1 || size<1 || size>100 || (keyword!=null && keyword.length()>100) || (categoryId!=null && categoryId<1))
            throw new ApiException(400,"分页或搜索参数不正确");
        if(status!=null && !status.isEmpty() && !OWN_STATES.contains(status)) throw new ApiException(400,"物品状态不正确");
        QueryWrapper<Item> query=new QueryWrapper<Item>().eq("owner_id",ownerId).in("status",OWN_STATES);
        if(status!=null && !status.isEmpty()) query.eq("status",status);
        if(keyword!=null && !keyword.isBlank()) query.like("title",keyword.trim());
        if(categoryId!=null) query.eq("category_id",categoryId);
        query.orderByDesc("created_at","id");
        Page<Item> result=baseMapper.selectPage(new Page<>(page,size),query);
        return new PageResult<>(views(result.getRecords(),true),result.getTotal(),page,size);
    }
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ItemView ownDetail(long ownerId,long id) {
        return views(List.of(requireOwned(baseMapper.selectById(id),ownerId)),true).get(0);
    }
    @Override @Transactional(isolation=Isolation.READ_COMMITTED)
    public ItemView edit(long ownerId,long id,EditItemRequest request) {
        Item item=lockEditable(ownerId,id,request.version(),Set.of("AVAILABLE","PENDING_REVIEW","REJECTED"));
        PublishItemRequest fields=request.fields();
        String image=validateFields(ownerId,fields);
        UpdateWrapper<Item> update=new UpdateWrapper<Item>()
            .set("title",fields.title().trim()).set("description",fields.description().trim())
            .set("category_id",fields.categoryId()).set("condition_level",fields.conditionLevel())
            .set("tags_json",encode(fields.tags())).set("wanted_category_id",fields.wantedCategoryId())
            .set("wanted_tags_json",encode(fields.wantedTags())).set("image_url",image)
            .set("status","PENDING_REVIEW").set("review_basis","UNREVIEWED");
        return advanceAndRead(item,update,"SUBMIT");
    }
    @Override @Transactional(isolation=Isolation.READ_COMMITTED)
    public ItemView withdraw(long ownerId,long id,WithdrawItemRequest request) {
        Item item=lockEditable(ownerId,id,request.version(),Set.of("AVAILABLE"));
        return advanceAndRead(item,new UpdateWrapper<Item>().set("status","HIDDEN"),"WITHDRAW");
    }
    private Item requireOwned(Item item,long ownerId) {
        if(item==null) throw new ApiException(404,"物品不存在");
        if(item.getOwnerId()!=ownerId) throw new ApiException(403,"只能访问本人拥有的物品");
        return item;
    }
    private Item lockEditable(long ownerId,long id,Integer version,Set<String> allowedStates) {
        if(version==null || version<0) throw new ApiException(400,"需要非负整数 version");
        Item item=requireOwned(baseMapper.selectForUpdate(id),ownerId);
        mutationGuard.requireVersion(item,version);
        if(!allowedStates.contains(item.getStatus()))
            throw new ApiException(409,"物品当前状态或交换占用不允许编辑或下架");
        mutationGuard.requireUnoccupied(item);
        return item;
    }
    private ItemView advanceAndRead(Item item,UpdateWrapper<Item> update,String action) {
        update.eq("id",item.getId()).eq("owner_id",item.getOwnerId()).eq("status",item.getStatus())
            .eq("version",item.getVersion()).set("version",item.getVersion()+1);
        if(baseMapper.update(null,update)!=1) throw new ApiException(409,"物品已更新，请刷新后重试");
        Item after=baseMapper.selectById(item.getId());
        audits.append(item.getOwnerId(),action,null,item,after);
        return views(List.of(after),true).get(0);
    }
    private String validateFields(long ownerId,PublishItemRequest request) {
        categorySelection.requireActive(Arrays.asList(request.categoryId(),request.wantedCategoryId()));
        String image=request.imageUrl();
        if(image!=null && !image.isBlank()) {
            uploads.publicImage(ownerId,image);
        }
        return image==null || image.isBlank()?null:image;
    }
    @Override public List<ItemView> availableForMatching() {
        List<Item> available=baseMapper.selectList(new QueryWrapper<Item>().eq("status","AVAILABLE").inSql("owner_id","SELECT id FROM cl_user WHERE status = 'ACTIVE'")
            .notExists("SELECT 1 FROM cl_item_hold h WHERE h.item_id = cl_item.id").orderByAsc("id").last("LIMIT 201"));
        if(available.size()>200) throw new ApiException(422,"初版匹配支持最多 200 件可交换物品；请先实现候选分区");
        return views(available);
    }
    private List<ItemView> views(List<Item> items) {return views(items,false);}
    private List<ItemView> views(List<Item> items,boolean includeReview) {
        if(items.isEmpty()) return List.of();
        Map<Long,String> names=users.selectBatchIds(items.stream().map(Item::getOwnerId).collect(Collectors.toSet())).stream().collect(Collectors.toMap(User::getId,User::getDisplayName));
        Map<Long,String> cats=categories.selectList(null).stream().collect(Collectors.toMap(Category::getId,Category::getName));
        var decisions=includeReview?audits.latestDecisions(items.stream().map(Item::getId).toList()):Map.<Long,edu.campusloop.web.review.entity.ItemReviewAudit>of();
        return items.stream().map(i->{var decision=decisions.get(i.getId());return new ItemView(i.getId(),i.getOwnerId(),names.get(i.getOwnerId()),i.getTitle(),i.getDescription(),i.getCategoryId(),
            cats.get(i.getCategoryId()),i.getConditionLevel(),decode(i.getTagsJson()),i.getWantedCategoryId(),cats.get(i.getWantedCategoryId()),
            decode(i.getWantedTagsJson()),i.getImageUrl(),i.getStatus(),i.getVersion(),i.getCreatedAt(),i.getReviewBasis(),
            decision==null?null:decision.getAction(),decision==null?null:decision.getReason(),
            decision==null?null:decision.getOperatorDisplayName(),decision==null?null:decision.getCreatedAt(),
            decision==null?null:decision.getPreviousVersion());}).toList();
    }
    private String encode(List<String> tags) {
        try {return json.writeValueAsString(tags.stream().map(String::trim).map(s->s.toLowerCase(Locale.ROOT)).distinct().toList());}
        catch(Exception e){throw new ApiException(400,"标签格式不正确");}
    }
    private List<String> decode(String tags) {
        try {return json.readValue(tags,new TypeReference<List<String>>(){});}
        catch(Exception e){throw new IllegalStateException("Stored tags are invalid");}
    }
}
