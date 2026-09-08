package edu.campusloop.web.favorite.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.campusloop.web.favorite.entity.Favorite;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface FavoriteMapper extends BaseMapper<Favorite> {
    // Add/remove share the item lock with lifecycle writes; no favorite operation changes the item.
    @Select("SELECT id FROM cl_item WHERE id = #{itemId} FOR UPDATE")
    Long lockItem(@Param("itemId") long itemId);
}
