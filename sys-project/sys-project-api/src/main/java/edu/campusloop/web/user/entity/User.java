package edu.campusloop.web.user.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
@Data
@TableName("cl_user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String passwordHash;
    private String displayName;
    private String role;
    private String status;
    private java.time.LocalDateTime createdAt;
}
