package edu.campusloop.web.report.vo;

public record ReportAuditView(long id,String action,ReportActorView actor,String previousStatus,String newStatus,
    Integer previousVersion,int newVersion,String reason,String decision,String createdAt) {}
