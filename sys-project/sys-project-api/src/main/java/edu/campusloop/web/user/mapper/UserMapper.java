package edu.campusloop.web.user.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.campusloop.web.user.entity.User;
import org.apache.ibatis.annotations.*;
import java.util.List;
public interface UserMapper extends BaseMapper<User> {
    @Select("SELECT * FROM cl_user WHERE role='ADMIN' ORDER BY id FOR UPDATE")
    List<User> selectAdminsForUpdate();

    @Select("SELECT * FROM cl_user WHERE id=#{id} FOR UPDATE")
    User selectByIdForUpdate(@Param("id") long id);

    @Update("UPDATE cl_user SET status=#{newStatus},version=version+1 WHERE id=#{id} AND status=#{previousStatus} AND version=#{version}")
    int updateStatusIfVersion(@Param("id") long id,@Param("previousStatus") String previousStatus,
                              @Param("newStatus") String newStatus,@Param("version") int version);
}
