package edu.campusloop.web.category.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
@Data
@TableName("cl_category")
public class Category {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
}
