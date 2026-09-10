package edu.campusloop.web.item.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
@Data
@TableName("cl_item")
public class Item {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ownerId;
    private String title;
    private String description;
    private Long categoryId;
    private Integer conditionLevel;
    private String tagsJson;
    private Long wantedCategoryId;
    private String wantedTagsJson;
    private String imageUrl;
    private String imageUrlsJson;
    private String status;
    private Integer version;
    private String reviewBasis;
    private java.time.LocalDateTime createdAt;
}
