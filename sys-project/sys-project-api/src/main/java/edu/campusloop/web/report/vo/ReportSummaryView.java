package edu.campusloop.web.report.vo;

public record ReportSummaryView(long id,String targetType,long targetId,boolean targetAvailable,String targetSummary,
    String reporterDisplayName,String status,int version,String createdAt,ReportActorView acceptedBy,String acceptedAt,
    String decision,String decisionReason,ReportActorView decidedBy,String decidedAt) {}
