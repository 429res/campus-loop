package edu.campusloop.web.report.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.campusloop.common.*;
import edu.campusloop.web.exchange.service.ExchangeDatabaseClock;
import edu.campusloop.web.item.entity.Item;
import edu.campusloop.web.item.mapper.ItemMapper;
import edu.campusloop.web.item.service.ItemVisibility;
import edu.campusloop.web.report.dto.*;
import edu.campusloop.web.report.entity.*;
import edu.campusloop.web.report.mapper.*;
import edu.campusloop.web.report.vo.*;
import edu.campusloop.web.upload.service.*;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.user.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ReportService {
    private static final Set<String> STATES=Set.of("SUBMITTED","IN_REVIEW","RESOLVED");
    private final ReportMapper reports;private final ReportEvidenceMapper evidence;private final ReportAuditMapper audits;
    private final UserMapper users;private final ItemMapper items;private final UploadReferenceService references;
    private final LocalUploadService files;private final ReportTransactionExecutor transactions;private final ExchangeDatabaseClock clock;
    private final ObjectMapper json;

    public ReportService(ReportMapper reports,ReportEvidenceMapper evidence,ReportAuditMapper audits,UserMapper users,
        ItemMapper items,UploadReferenceService references,LocalUploadService files,ReportTransactionExecutor transactions,
        ExchangeDatabaseClock clock,ObjectMapper json) {
        this.reports=reports;this.evidence=evidence;this.audits=audits;this.users=users;this.items=items;
        this.references=references;this.files=files;this.transactions=transactions;this.clock=clock;this.json=json;
    }

    public ReportDetailView create(long actor,CreateReportCommand command) {
        String requestDigest=createDigest(command);
        long reportId=transactions.execute(()-> {
            User reporter=active(users.selectByIdForUpdate(actor));
            Report replay=reports.selectReplay(actor,command.idempotencyKey());
            if(replay!=null) {
                if(!requestDigest.equals(replay.getRequestDigest())) throw conflict();
                return replay.getId();
            }
            Item target=items.selectForUpdate(command.targetId());
            if(target==null || !ItemVisibility.PUBLIC_STATES.contains(target.getStatus()))
                throw new ApiException(404,"举报目标不存在或不可提交");
            if(reports.selectOpen(actor,command.targetType(),command.targetId())!=null)
                throw new ApiException(409,"同一目标已有待处理举报，请等待处理结果");
            List<EvidenceSnapshot> snapshots=snapshots(actor,command.evidenceUploadIds());
            LocalDateTime now=clock.now();
            Report row=new Report();row.setReporterId(actor);row.setReporterDisplayName(reporter.getDisplayName());
            row.setTargetType(command.targetType());row.setTargetId(command.targetId());
            row.setTargetSummary(target.getTitle()+" · ITEM #"+target.getId());row.setReason(command.reason());
            row.setIdempotencyKey(command.idempotencyKey());row.setRequestDigest(requestDigest);
            row.setStatus("SUBMITTED");row.setVersion(0);row.setCreatedAt(now);reports.insert(row);
            for(int index=0;index<snapshots.size();index++) {
                EvidenceSnapshot snapshot=snapshots.get(index);ReportEvidence link=new ReportEvidence();
                link.setReportId(row.getId());link.setUploadId(snapshot.uploadId());link.setPositionNo(index);
                link.setDisplayName("证据-"+(index+1)+".png");link.setContentType("image/png");
                link.setSizeBytes(snapshot.size());link.setContentHash(snapshot.hash());evidence.insert(link);
            }
            audits.insert(audit(row.getId(),reporter,"SUBMIT",null,"SUBMITTED",null,0,command.reason(),null,requestDigest,now));
            return row.getId();
        });
        return mineDetail(actor,reportId);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResult<ReportSummaryView> minePage(long actor,int page,int size,String status,String targetType) {
        active(users.selectById(actor));validatePage(page,size);validateFilters(status,targetType,null);
        QueryWrapper<Report> query=new QueryWrapper<Report>().eq("reporter_id",actor);
        filtered(query,status,targetType);query.orderByDesc("created_at","id");
        Page<Report> result=reports.selectPage(new Page<>(page,size),query);
        return new PageResult<>(summaries(result.getRecords()),result.getTotal(),page,size);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ReportDetailView mineDetail(long actor,long id) {
        active(users.selectById(actor));Report row=report(id);
        if(row.getReporterId()!=actor) throw new ApiException(404,"举报不存在");
        return detail(row);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResult<ReportSummaryView> adminPage(long actor,int page,int size,String keyword,String status,String targetType) {
        admin(users.selectById(actor));validatePage(page,size);validateFilters(status,targetType,keyword);
        QueryWrapper<Report> query=new QueryWrapper<>();filtered(query,status,targetType);
        if(keyword!=null && !keyword.isBlank()) {
            String value=keyword.trim();Long id=positiveLong(value);
            if(id==null) query.like("target_summary",value);
            else query.and(part->part.eq("id",id).or().like("target_summary",value));
        }
        query.orderByDesc("created_at","id");Page<Report> result=reports.selectPage(new Page<>(page,size),query);
        return new PageResult<>(summaries(result.getRecords()),result.getTotal(),page,size);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ReportDetailView adminDetail(long actor,long id) {admin(users.selectById(actor));return detail(report(id));}

    public ReportDetailView accept(long actor,long id,AcceptReportCommand command) {
        String digest=actionDigest("ACCEPT",id,actor,command.version(),null,command.reason());
        transactions.execute(()-> {
            User administrator=admin(users.selectByIdForUpdate(actor));Report row=lockedReport(id);
            if(replayed(row,"ACCEPT",actor,digest)) return true;
            if(!"SUBMITTED".equals(row.getStatus()) || row.getVersion()!=command.version()) throw conflict();
            LocalDateTime now=clock.now();
            if(reports.accept(id,command.version(),actor,now)!=1) throw conflict();
            audits.insert(audit(id,administrator,"ACCEPT",row.getStatus(),"IN_REVIEW",row.getVersion(),row.getVersion()+1,
                command.reason(),null,digest,now));return true;
        });
        return adminDetail(actor,id);
    }

    public ReportDetailView decide(long actor,long id,DecideReportCommand command) {
        String digest=actionDigest("DECIDE",id,actor,command.version(),command.decision(),command.reason());
        transactions.execute(()-> {
            User administrator=admin(users.selectByIdForUpdate(actor));Report row=lockedReport(id);
            if(replayed(row,"DECIDE",actor,digest)) return true;
            if(!"IN_REVIEW".equals(row.getStatus()) || row.getVersion()!=command.version()) throw conflict();
            LocalDateTime now=clock.now();
            if(reports.decide(id,command.version(),command.decision(),command.reason(),actor,now)!=1) throw conflict();
            audits.insert(audit(id,administrator,"DECIDE",row.getStatus(),"RESOLVED",row.getVersion(),row.getVersion()+1,
                command.reason(),command.decision(),digest,now));return true;
        });
        return adminDetail(actor,id);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResult<ReportAuditView> audits(long actor,long reportId,int page,int size) {
        admin(users.selectById(actor));validatePage(page,size);report(reportId);
        QueryWrapper<ReportAudit> query=new QueryWrapper<ReportAudit>().eq("report_id",reportId).orderByAsc("created_at","id");
        Page<ReportAudit> result=audits.selectPage(new Page<>(page,size),query);
        return new PageResult<>(result.getRecords().stream().map(this::auditView).toList(),result.getTotal(),page,size);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public EvidenceContent mineEvidence(long actor,long reportId,long evidenceId) {
        active(users.selectById(actor));Report row=report(reportId);
        if(row.getReporterId()!=actor) throw new ApiException(404,"举报证据不存在或不可访问");
        return content(row,evidenceId);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public EvidenceContent adminEvidence(long actor,long reportId,long evidenceId) {
        admin(users.selectById(actor));return content(report(reportId),evidenceId);
    }

    private EvidenceContent content(Report report,long evidenceId) {
        if(evidenceId<1) throw new ApiException(400,"证据ID不正确");
        ReportEvidence row=evidence.selectForReportAndId(report.getId(),evidenceId);
        if(row==null) throw new ApiException(404,"举报证据不存在或不可访问");
        return new EvidenceContent(readEvidence(report,row),row.getContentType());
    }

    private ReportDetailView detail(Report row) {
        ReportSummaryView summary=summaries(List.of(row)).get(0);
        List<ReportEvidenceView> links=evidence.selectForReport(row.getId()).stream().map(link->new ReportEvidenceView(
            link.getId(),link.getDisplayName(),link.getContentType(),link.getSizeBytes(),available(row,link)?"AVAILABLE":"MISSING")).toList();
        return new ReportDetailView(summary.id(),summary.targetType(),summary.targetId(),summary.targetAvailable(),
            summary.targetSummary(),summary.reporterDisplayName(),summary.status(),summary.version(),row.getReason(),links,
            summary.createdAt(),summary.acceptedBy(),summary.acceptedAt(),summary.decision(),summary.decisionReason(),
            summary.decidedBy(),summary.decidedAt());
    }

    private List<ReportSummaryView> summaries(List<Report> rows) {
        if(rows.isEmpty()) return List.of();
        Set<Long> itemIds=rows.stream().map(Report::getTargetId).collect(Collectors.toSet());
        Map<Long,Item> targets=items.selectBatchIds(itemIds).stream().collect(Collectors.toMap(Item::getId,Function.identity()));
        Set<Long> actorIds=rows.stream().flatMap(row->java.util.stream.Stream.of(row.getAcceptedBy(),row.getDecidedBy()))
            .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long,User> actors=actorIds.isEmpty()?Map.of():users.selectBatchIds(actorIds).stream().collect(Collectors.toMap(User::getId,Function.identity()));
        return rows.stream().map(row->{Item target=targets.get(row.getTargetId());return new ReportSummaryView(row.getId(),row.getTargetType(),
            row.getTargetId(),target!=null && ItemVisibility.PUBLIC_STATES.contains(target.getStatus()),row.getTargetSummary(),
            row.getReporterDisplayName(),row.getStatus(),row.getVersion(),utc(row.getCreatedAt()),actor(row.getAcceptedBy(),actors),
            utc(row.getAcceptedAt()),row.getDecision(),row.getDecisionReason(),actor(row.getDecidedBy(),actors),utc(row.getDecidedAt()));}).toList();
    }

    private boolean available(Report report,ReportEvidence link) {
        try {
            references.privateEvidence(report.getReporterId(),link.getUploadId());
            return Files.size(files.evidencePath(link.getUploadId()))==link.getSizeBytes();
        } catch(IOException | ApiException missing) {return false;}
    }

    private byte[] readEvidence(Report report,ReportEvidence link) {
        try {
            references.privateEvidence(report.getReporterId(),link.getUploadId());
            byte[] bytes=Files.readAllBytes(files.evidencePath(link.getUploadId()));
            if(bytes.length!=link.getSizeBytes() || !hash(bytes).equals(link.getContentHash())) throw missingEvidence();
            return bytes;
        } catch(IOException | ApiException failure) {throw missingEvidence();}
    }

    private List<EvidenceSnapshot> snapshots(long actor,List<String> ids) {
        List<EvidenceSnapshot> out=new ArrayList<>();
        for(String id:ids) {
            try {
                references.privateEvidence(actor,id);byte[] bytes=Files.readAllBytes(files.evidencePath(id));
                if(bytes.length<1 || bytes.length>5*1024*1024) throw new IOException();
                out.add(new EvidenceSnapshot(id,bytes.length,hash(bytes)));
            } catch(IOException | ApiException invalid) {
                throw new ApiException(400,"请使用本人上传且符合用途的有效图片引用");
            }
        }
        return List.copyOf(out);
    }

    private boolean replayed(Report row,String action,long actor,String digest) {
        ReportAudit prior=audits.selectAction(row.getId(),action);
        if(prior==null) return false;
        if(prior.getActorUserId()==actor && digest.equals(prior.getRequestDigest())) return true;
        throw conflict();
    }

    private ReportAudit audit(long reportId,User actor,String action,String previousStatus,String newStatus,Integer previousVersion,
        int newVersion,String reason,String decision,String digest,LocalDateTime at) {
        ReportAudit row=new ReportAudit();row.setReportId(reportId);row.setActorUserId(actor.getId());row.setActorDisplayName(actor.getDisplayName());
        row.setAction(action);row.setPreviousStatus(previousStatus);row.setNewStatus(newStatus);row.setPreviousVersion(previousVersion);
        row.setNewVersion(newVersion);row.setReason(reason);row.setDecision(decision);row.setRequestDigest(digest);row.setCreatedAt(at);return row;
    }

    private ReportAuditView auditView(ReportAudit row) {return new ReportAuditView(row.getId(),row.getAction(),new ReportActorView(row.getActorDisplayName()),
        row.getPreviousStatus(),row.getNewStatus(),row.getPreviousVersion(),row.getNewVersion(),row.getReason(),row.getDecision(),utc(row.getCreatedAt()));}

    private static ReportActorView actor(Long id,Map<Long,User> actors) {
        if(id==null) return null;User user=actors.get(id);return user==null?null:new ReportActorView(user.getDisplayName());
    }

    private static void filtered(QueryWrapper<Report> query,String status,String targetType) {
        if(status!=null && !status.isBlank()) query.eq("status",status);
        if(targetType!=null && !targetType.isBlank()) query.eq("target_type",targetType);
    }

    private static void validatePage(int page,int size) {
        if(page<1 || size<1 || size>100) throw new ApiException(400,"分页参数不正确");
    }

    private static void validateFilters(String status,String targetType,String keyword) {
        if(status!=null && !status.isBlank() && !STATES.contains(status) ||
            targetType!=null && !targetType.isBlank() && !"ITEM".equals(targetType) || keyword!=null && keyword.length()>100)
            throw new ApiException(400,"举报筛选参数不正确");
    }

    private Report report(long id) {
        if(id<1) throw new ApiException(400,"举报ID不正确");Report row=reports.selectById(id);
        if(row==null) throw new ApiException(404,"举报不存在");return row;
    }
    private Report lockedReport(long id) {
        if(id<1) throw new ApiException(400,"举报ID不正确");Report row=reports.selectForUpdate(id);
        if(row==null) throw new ApiException(404,"举报不存在");return row;
    }
    private static User active(User user) {
        if(user==null || !"ACTIVE".equals(user.getStatus()) || user.getPasswordHash()==null) throw new ApiException(401,"账号不可用");return user;
    }
    private static User admin(User user) {
        active(user);if(!"ADMIN".equals(user.getRole())) throw new ApiException(403,"需要管理员权限");return user;
    }
    private String createDigest(CreateReportCommand command) {
        ObjectNode value=json.createObjectNode();value.put("targetType",command.targetType());value.put("targetId",command.targetId());
        value.put("reason",command.reason());value.set("evidenceUploadIds",json.valueToTree(command.evidenceUploadIds()));return hash(value.toString());
    }
    private String actionDigest(String action,long reportId,long actor,int version,String decision,String reason) {
        ObjectNode value=json.createObjectNode();value.put("action",action);value.put("reportId",reportId);value.put("actorId",actor);
        value.put("version",version);if(decision==null)value.putNull("decision");else value.put("decision",decision);value.put("reason",reason);return hash(value.toString());
    }
    private static Long positiveLong(String value) {try {long result=Long.parseLong(value);return result>0?result:null;}catch(NumberFormatException bad){return null;}}
    private static String utc(LocalDateTime value) {return value==null?null:value.toInstant(ZoneOffset.UTC).toString();}
    private static String hash(String value) {return hash(value.getBytes(StandardCharsets.UTF_8));}
    private static String hash(byte[] value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static ApiException conflict() {return new ApiException(409,"举报状态、版本或重复请求内容已变化，请刷新后重试");}
    private static ApiException missingEvidence() {return new ApiException(404,"举报证据不存在或不可访问");}
    private record EvidenceSnapshot(String uploadId,long size,String hash) {}
    public record EvidenceContent(byte[] bytes,String contentType) {}
}
