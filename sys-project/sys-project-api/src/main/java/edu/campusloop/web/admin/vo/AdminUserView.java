package edu.campusloop.web.admin.vo;

import java.time.LocalDateTime;

public record AdminUserView(long id,String username,String displayName,String role,String status,int version,LocalDateTime createdAt,java.util.List<String> permissions) {}
