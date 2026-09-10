package edu.campusloop.web.spotlight.service;
import edu.campusloop.common.ApiException;
import edu.campusloop.auth.AdminPermissions;
import edu.campusloop.web.user.mapper.UserMapper;
import edu.campusloop.web.item.service.ItemService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service public class SpotlightService {
 private final JdbcTemplate db;private final ItemService items;private final UserMapper users;
 public SpotlightService(JdbcTemplate db,ItemService items,UserMapper users){this.db=db;this.items=items;this.users=users;}
 public List<edu.campusloop.web.item.vo.ItemView> visible(){var ids=db.queryForList("SELECT s.item_id FROM cl_item_spotlight s JOIN cl_item i ON i.id=s.item_id JOIN cl_user u ON u.id=i.owner_id WHERE i.status='AVAILABLE' AND u.status='ACTIVE' ORDER BY s.sort_order,s.item_id LIMIT 8",Long.class);var rows=items.visibleDetails(ids);return ids.stream().map(id->rows.stream().filter(i->i.id()==id).findFirst().orElse(null)).filter(Objects::nonNull).toList();}
 public List<Map<String,Object>> settings(){return db.queryForList("SELECT s.item_id AS itemId,s.sort_order AS sortOrder,i.title,i.status FROM cl_item_spotlight s JOIN cl_item i ON i.id=s.item_id ORDER BY s.sort_order,s.item_id");}
 @Transactional public void save(long actor,long item,boolean enabled,int order){var admins=users.selectAdminsForUpdate();var admin=admins.stream().filter(u->u.getId()==actor).findFirst().orElseThrow(()->new ApiException(403,"需要管理员权限"));AdminPermissions.require(admin,"ITEMS");if(!enabled){db.update("DELETE FROM cl_item_spotlight WHERE item_id=?",item);return;}
  var owner=db.queryForList("SELECT owner_id FROM cl_item WHERE id=?",Long.class,item);if(owner.isEmpty())throw new ApiException(404,"物品不存在");users.selectByIdForUpdate(owner.get(0));
  var rows=db.queryForList("SELECT i.id FROM cl_item i JOIN cl_user u ON u.id=i.owner_id WHERE i.id=? AND i.status='AVAILABLE' AND u.status='ACTIVE' FOR UPDATE",Long.class,item);if(rows.isEmpty())throw new ApiException(409,"只有可交换物品能加入曝光");
  boolean exists=db.queryForObject("SELECT COUNT(*) FROM cl_item_spotlight WHERE item_id=?",Long.class,item)>0;if(!exists&&db.queryForObject("SELECT COUNT(*) FROM cl_item_spotlight",Long.class)>=8)throw new ApiException(409,"最多曝光 8 件物品，请先移除其他物品");
  if(exists)db.update("UPDATE cl_item_spotlight SET sort_order=? WHERE item_id=?",order,item);else db.update("INSERT INTO cl_item_spotlight(item_id,sort_order) VALUES(?,?)",item,order);
 }
}
