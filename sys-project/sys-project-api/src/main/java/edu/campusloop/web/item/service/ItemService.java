package edu.campusloop.web.item.service;
import edu.campusloop.common.PageResult;
import edu.campusloop.web.item.dto.PublishItemRequest;
import edu.campusloop.web.item.vo.ItemView;
import java.util.List;
public interface ItemService {
    PageResult<ItemView> page(int page,int size,String keyword,Long categoryId,boolean admin);
    ItemView detail(long id);
    ItemView publish(long ownerId,PublishItemRequest request);
    List<ItemView> availableForMatching();
}
