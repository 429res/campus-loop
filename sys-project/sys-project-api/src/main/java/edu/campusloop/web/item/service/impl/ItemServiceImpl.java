package edu.campusloop.web.item.service.impl;
import edu.campusloop.common.*;
import edu.campusloop.web.item.service.ItemService;
import edu.campusloop.web.item.entity.Item;
import edu.campusloop.web.item.mapper.ItemMapper;
import edu.campusloop.web.item.dto.PublishItemRequest;
import edu.campusloop.web.item.vo.ItemView;
import edu.campusloop.web.category.entity.Category;
import edu.campusloop.web.category.mapper.CategoryMapper;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.user.mapper.UserMapper;
import edu.campusloop.web.upload.entity.Upload;
import edu.campusloop.web.upload.mapper.UploadMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
@Service
public class ItemServiceImpl extends ServiceImpl<ItemMapper,Item> implements ItemService {
    private final CategoryMapper categories;private final UserMapper users;private final UploadMapper uploads;private final ObjectMapper json;
    public ItemServiceImpl(CategoryMapper categories,UserMapper users,UploadMapper uploads,ObjectMapper json) {
        this.categories=categories;this.users=users;this.uploads=uploads;this.json=json;
    }
    @Override public PageResult<ItemView> page(int page,int size,String keyword,Long categoryId,boolean admin) {
        if(page<1 || size<1 || size>100 || (keyword!=null && keyword.length()>100)) throw new ApiException(400,"分页或搜索参数不正确");
        QueryWrapper<Item> query=new QueryWrapper<>();
        if(!admin) query.in("status","AVAILABLE","RESERVED","EXCHANGED");
        if(keyword!=null && !keyword.isBlank()) query.like("title",keyword.trim());
        if(categoryId!=null) query.eq("category_id",categoryId);
        query.orderByDesc("created_at","id");
        Page<Item> result=baseMapper.selectPage(new Page<>(page,size),query);
        return new PageResult<>(views(result.getRecords()),result.getTotal(),page,size);
    }
    @Override public ItemView detail(long id) {
        Item item=baseMapper.selectById(id);
        if(item==null || !Set.of("AVAILABLE","RESERVED","EXCHANGED").contains(item.getStatus())) throw new ApiException(404,"物品不存在或暂不可见");
        return views(List.of(item)).get(0);
    }
    @Override @Transactional public ItemView publish(long ownerId,PublishItemRequest request) {
        if(categories.selectById(request.categoryId())==null || categories.selectById(request.wantedCategoryId())==null) throw new ApiException(400,"分类不存在");
        String image=request.imageUrl();
        if(image!=null && !image.isBlank()) {
            if(!image.matches("/uploads/[a-f0-9-]{36}\\.png") || uploads.selectCount(new QueryWrapper<Upload>().eq("url",image).eq("owner_id",ownerId))!=1)
                throw new ApiException(400,"请使用本人上传的图片");
        }
        Item item=new Item();item.setOwnerId(ownerId);item.setTitle(request.title().trim());item.setDescription(request.description().trim());
        item.setCategoryId(request.categoryId());item.setConditionLevel(request.conditionLevel());item.setTagsJson(encode(request.tags()));
        item.setWantedCategoryId(request.wantedCategoryId());item.setWantedTagsJson(encode(request.wantedTags()));
        item.setImageUrl(image==null || image.isBlank()?null:image);item.setStatus("AVAILABLE");item.setVersion(0);item.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        baseMapper.insert(item);return views(List.of(item)).get(0);
    }
    @Override public List<ItemView> availableForMatching() {
        List<Item> available=baseMapper.selectList(new QueryWrapper<Item>().eq("status","AVAILABLE").inSql("owner_id","SELECT id FROM cl_user WHERE status = 'ACTIVE'")
            .notExists("SELECT 1 FROM cl_item_hold h WHERE h.item_id = cl_item.id").orderByAsc("id").last("LIMIT 201"));
        if(available.size()>200) throw new ApiException(422,"初版匹配支持最多 200 件可交换物品；请先实现候选分区");
        return views(available);
    }
    private List<ItemView> views(List<Item> items) {
        if(items.isEmpty()) return List.of();
        Map<Long,String> names=users.selectBatchIds(items.stream().map(Item::getOwnerId).collect(Collectors.toSet())).stream().collect(Collectors.toMap(User::getId,User::getDisplayName));
        Map<Long,String> cats=categories.selectList(null).stream().collect(Collectors.toMap(Category::getId,Category::getName));
        return items.stream().map(i->new ItemView(i.getId(),i.getOwnerId(),names.get(i.getOwnerId()),i.getTitle(),i.getDescription(),i.getCategoryId(),
            cats.get(i.getCategoryId()),i.getConditionLevel(),decode(i.getTagsJson()),i.getWantedCategoryId(),cats.get(i.getWantedCategoryId()),
            decode(i.getWantedTagsJson()),i.getImageUrl(),i.getStatus(),i.getCreatedAt())).toList();
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
