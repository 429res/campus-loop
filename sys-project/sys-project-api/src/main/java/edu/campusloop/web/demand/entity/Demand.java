package edu.campusloop.web.demand.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("cl_demand")
public class Demand {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ownerId;
    private Long categoryId;
    private String description;
    private String preferredTagsJson;
    private String status;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
