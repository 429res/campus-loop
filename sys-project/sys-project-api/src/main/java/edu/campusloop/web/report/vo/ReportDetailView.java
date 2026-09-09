package edu.campusloop.web.report.vo;

import java.util.List;

public record ReportDetailView(long id,String targetType,long targetId,boolean targetAvailable,String targetSummary,
    String reporterDisplayName,String status,int version,String reason,List<ReportEvidenceView> evidence,String createdAt,
    ReportActorView acceptedBy,String acceptedAt,String decision,String decisionReason,ReportActorView decidedBy,String decidedAt) {}
