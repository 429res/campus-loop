package edu.campusloop.web.auth.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
@Data
@TableName("cl_auth_session")
public class AuthSession {
    @TableId(type = IdType.INPUT)
    private String id;
    private Long userId;
    private java.time.LocalDateTime expiresAt;
}
