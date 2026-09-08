package edu.campusloop.web.history.service;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.campusloop.common.*;
import edu.campusloop.web.history.dto.HistoryVerificationCommand;
import edu.campusloop.web.history.entity.HistoryEvent;
import edu.campusloop.web.history.mapper.*;
import edu.campusloop.web.exchange.mapper.*;
import edu.campusloop.web.exchange.service.*;
import edu.campusloop.web.item.mapper.ItemMapper;
import edu.campusloop.web.upload.service.LocalUploadService;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.user.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

@Service
public class HistoryVerificationService {
    private final HistoryMapper history;private final HistoryVerificationMapper records;private final UserMapper users;
    private final ItemMapper items;private final ExchangeLifecycleMapper locks;private final ExchangeMapper exchanges;
    private final HistoryConfirmationService confirmations;private final LocalUploadService files;private final edu.campusloop.web.upload.service.UploadReferenceService references;private final ObjectMapper json;
    private final ExchangeTransactionExecutor transactions;private final ExchangeDatabaseClock clock;
    public HistoryVerificationService(HistoryMapper history,HistoryVerificationMapper records,UserMapper users,ItemMapper items,
        ExchangeLifecycleMapper locks,ExchangeMapper exchanges,HistoryConfirmationService confirmations,LocalUploadService files,
        ObjectMapper json,ExchangeTransactionExecutor transactions,ExchangeDatabaseClock clock,edu.campusloop.web.upload.service.UploadReferenceService references) {
        this.history=history;this.records=records;this.users=users;this.items=items;this.locks=locks;this.exchanges=exchanges;
        this.confirmations=confirmations;this.files=files;this.json=json;this.transactions=transactions;this.clock=clock;this.references=references;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResult<JsonNode> page(long actor,int page,int size,String status) {
        var admin=admin(users.selectById(actor));
        if(page<1 || size<1 || size>100 || status!=null && !Set.of("PENDING","APPROVED","REJECTED","SUPERSEDED").contains(status)) throw new ApiException(400,"核验队列参数不正确");
        return new PageResult<>(records.page(status,size,((long)page-1)*size).stream().map(id->detail(admin,row(id))).toList(),records.count(status),page,size);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public JsonNode detail(long actor,long id) {return detail(admin(users.selectById(actor)),row(id));}
    private JsonNode detail(User admin,HistoryEvent row) {
        var audit=records.audit(row.getId());var snapshot=snapshot(admin,row);
        ObjectNode out=json.createObjectNode();out.put("eventId",row.getId());out.put("itemId",row.getItemId());out.put("version",audit==null?0:1);
        out.put("status",audit!=null?audit.decision():row.getCorrectedByEventId()!=null?"SUPERSEDED":"PENDING");
        out.set("content",snapshot);out.put("snapshotHash",hash(snapshot.toString()));out.set("verification",view(admin,row));
        var actions=out.putArray("allowedActions");
        if(audit==null && eligible(row) && row.getCorrectedByEventId()==null && !involved(admin.getId(),row)) {
            if(snapshot.path("approvalEvidenceSatisfied").asBoolean()) actions.add("APPROVE");actions.add("REJECT");
        }
        return out;
    }
    public void decide(long actor,long id,HistoryVerificationCommand command) {
        ObjectNode payload=json.createObjectNode();payload.put("eventId",id);payload.set("command",json.valueToTree(command));String request=payload.toString();
        transactions.execute("履历核验",()-> {
            var before=row(id);
            if(before.getExchangeId()!=null) locks.lock(before.getExchangeId());
            var admin=admin(users.selectByIdForUpdate(actor));
            var replay=records.replay(actor,command.idempotencyKey());
            if(replay!=null) {if(!replay.requestJson().equals(request)) throw conflict();return true;}
            if(items.selectForUpdate(before.getItemId())==null) throw conflict();
            // A separate event-only locking statement bypasses pre-lock MyBatis cache without locking the author.
            var current=records.current(id);
            if(involved(actor,current)) throw new ApiException(403,"不得核验本人声明或本人参与的交换");
            if(!eligible(current) || current.getCorrectedByEventId()!=null || records.audit(id)!=null || command.version()!=0) throw conflict();
            var snapshot=snapshot(admin,current);String digest=hash(snapshot.toString());
            if(!digest.equals(command.snapshotHash()) || command.decision().equals("APPROVED") && !snapshot.path("approvalEvidenceSatisfied").asBoolean()) throw conflict();
            records.prepare(id);
            if(records.decide(id,command.version(),command.decision())!=1) throw conflict();
            records.append(new HistoryVerificationMapper.Audit(id,1,actor,command.idempotencyKey(),request,command.decision(),command.scope(),command.reason(),digest,snapshot.toString(),clock.now()));
            return true;
        });
    }
    /** Public projection contains no private reasons, identifiers, hashes or evidence. */
    public JsonNode view(User viewer,HistoryEvent row) {
        var audit=records.audit(row.getId());if(audit==null) return json.nullNode();
        boolean privateAccess=viewer!=null && "ADMIN".equals(viewer.getRole());
        var out=json.createObjectNode();out.put("decision",audit.decision());out.put("scope",audit.scope());out.put("decidedAt",utc(audit.decidedAt()));out.put("currentContent",row.getCorrectedByEventId()==null);
        if(privateAccess) {out.put("adminId",audit.adminId());out.put("version",audit.version());out.put("reason",audit.reason());out.put("snapshotHash",audit.snapshotHash());out.set("snapshot",parse(audit.snapshotJson()));}
        else {for(String field:List.of("adminId","version","reason","snapshotHash","snapshot")) out.putNull(field);}
        return out;
    }
    private ObjectNode snapshot(User admin,HistoryEvent row) {
        ObjectNode out=json.createObjectNode();out.put("ruleVersion","history-verification-v1");out.put("eventId",row.getId());out.put("itemId",row.getItemId());out.put("eventType",row.getEventType());out.put("statement",row.getDescription());
        out.put("authorId",row.getSourceUserId());out.put("relatedExchangeId",row.getExchangeId());out.put("correctsEventId",row.getCorrectsEventId());out.put("correctedByEventId",row.getCorrectedByEventId());
        out.put("recordedEvidenceLevel",row.getEvidenceLevel());out.put("occurredAt",utc(row.getOccurredAt()));out.put("recordedAt",utc(row.getRecordedAt()));
        var confirmation=confirmations.view(admin,row);out.set("confirmation",json.valueToTree(confirmation));
        // Permission-dependent buttons are not part of the evidence version seen by different admins.
        ((ObjectNode)out.get("confirmation")).remove("allowedActions");
        var evidence=out.putArray("evidence");int readable=0;var ids=history.evidenceIds(row.getId());
        for(String id:ids) {
            var entry=evidence.addObject().put("uploadId",id).put("url","/api/history-evidence/"+id).put("mediaType","image/png");
            try {references.privateEvidence(row.getSourceUserId(),id);var path=files.evidencePath(id);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new java.io.IOException();entry.put("sha256",hash(Files.readAllBytes(path)));readable++;}
            catch(java.io.IOException | ApiException missing) {entry.putNull("sha256");}
        }
        boolean enough="EXCHANGED".equals(row.getEventType())?"B04_HANDOFF".equals(confirmation.mode()) && "COMPLETE".equals(confirmation.status()):readable>0;
        out.put("approvalEvidenceSatisfied",enough);return out;
    }
    private boolean involved(long actor,HistoryEvent row) {return row.getSourceUserId()==actor || row.getExchangeId()!=null && exchanges.participants(List.of(row.getExchangeId())).stream().anyMatch(p->p.getUserId()==actor);}
    private static boolean eligible(HistoryEvent row) {return "SELF_REPORTED".equals(row.getEvidenceLevel()) && Set.of("REPAIR","TRANSFER").contains(row.getEventType()) || "BOTH_CONFIRMED".equals(row.getEvidenceLevel()) && "EXCHANGED".equals(row.getEventType());}
    private HistoryEvent row(long id) {var row=history.find(id);if(row==null) throw new ApiException(404,"履历不存在");return row;}
    private static User admin(User user) {if(user==null || !"ACTIVE".equals(user.getStatus()) || user.getPasswordHash()==null) throw new ApiException(401,"账号不可用");if(!"ADMIN".equals(user.getRole())) throw new ApiException(403,"需要管理员权限");return user;}
    private JsonNode parse(String value) {try{return json.readTree(value);}catch(Exception malformed){throw conflict();}}
    private static String utc(LocalDateTime time) {return time==null?null:time.toInstant(ZoneOffset.UTC).toString();}
    private static String hash(String value) {return hash(value.getBytes(StandardCharsets.UTF_8));}
    private static String hash(byte[] value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static ApiException conflict() {return new ApiException(409,"核验版本、快照、状态或幂等请求已变化，请刷新详情");}
}
