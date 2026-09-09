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
    private Boolean systemAccount;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String passwordHash;
    private String displayName;
    private String role;
    private String avatarUrl;
    private String bio;
    private String coverUrl;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String email;
    private Boolean emailVerified;
    private Boolean legacyExchangeAccess;
    private Boolean mailNotifications;
    private java.time.LocalDateTime deletedAt;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String lastLoginAddress;
    private String campus;
    private String contact;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String adminPermissions;
    private String status;
    private Integer version;
    private java.time.LocalDateTime createdAt;
}
