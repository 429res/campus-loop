package edu.campusloop.web.upload.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
@Data
@TableName("cl_upload")
public class Upload {
    @TableId(type = IdType.INPUT)
    private String id;
    private Long ownerId;
    private String url;
    private String visibility;
    private java.time.LocalDateTime createdAt;
}
