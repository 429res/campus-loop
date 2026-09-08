package edu.campusloop.web.category.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.campusloop.web.category.entity.Category;
import org.apache.ibatis.annotations.Select;

public interface CategoryMapper extends BaseMapper<Category> {
    @Select("SELECT * FROM cl_category WHERE id=#{id} FOR UPDATE")
    Category selectForUpdate(long id);

    // Do not lock referencing rows: item/demand writers acquire those before category locks.
    @Select("SELECT (SELECT COUNT(*) FROM cl_item WHERE category_id=#{id} OR wanted_category_id=#{id}) + " +
        "(SELECT COUNT(*) FROM cl_demand WHERE category_id=#{id})")
    long referenceCount(long id);
}
