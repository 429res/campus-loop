package edu.campusloop.web.admin.vo;

import java.time.LocalDateTime;

public record UserStatusAuditView(long id,long targetUserId,long operatorUserId,String operatorUsername,
                                  String operatorDisplayName,String previousStatus,String newStatus,String reason,
                                  int previousVersion,int newVersion,LocalDateTime createdAt) {}
