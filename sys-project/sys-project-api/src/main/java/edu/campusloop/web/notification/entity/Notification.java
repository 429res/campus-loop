package edu.campusloop.web.notification.entity;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
@Data @TableName("cl_notification")
public class Notification {
 @TableId(type=IdType.AUTO) private Long id;
 @com.fasterxml.jackson.annotation.JsonIgnore private Long userId;
 private String kind,title,body,link;
 @com.fasterxml.jackson.annotation.JsonIgnore private String sourceKey;
 @com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss'Z'") private LocalDateTime createdAt;
 @com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss'Z'") private LocalDateTime readAt;
}
