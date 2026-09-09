package edu.campusloop.web.item.service;
import edu.campusloop.common.PageResult;
import edu.campusloop.web.item.dto.*;
import edu.campusloop.web.item.vo.ItemView;
import java.util.List;
public interface ItemService {
    PageResult<ItemView> page(int page,int size,String keyword,Long categoryId,boolean admin);
    ItemView detail(long id);
    PageResult<ItemView> memberPage(long ownerId,int page,int size);
    PageResult<ItemView> adminPage(int page,int size,String keyword,Long categoryId,String status);
    ItemView adminDetail(long id);
    List<ItemView> visibleDetails(List<Long> ids);
    ItemView publish(long ownerId,PublishItemRequest request);
    PageResult<ItemView> ownPage(long ownerId,int page,int size,String keyword,Long categoryId,String status);
    ItemView ownDetail(long ownerId,long id);
    ItemView edit(long ownerId,long id,EditItemRequest request);
    ItemView withdraw(long ownerId,long id,WithdrawItemRequest request);
    ItemView relist(long ownerId,long id,WithdrawItemRequest request);
    List<ItemView> availableForMatching();
}
