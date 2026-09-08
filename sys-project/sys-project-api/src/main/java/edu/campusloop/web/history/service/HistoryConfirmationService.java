package edu.campusloop.web.history.service;

import com.fasterxml.jackson.databind.*;
import edu.campusloop.common.ApiException;
import edu.campusloop.web.exchange.entity.ExchangeParticipantRecord;
import edu.campusloop.web.exchange.mapper.*;
import edu.campusloop.web.exchange.service.*;
import edu.campusloop.web.history.dto.HistoryConfirmationCommand;
import edu.campusloop.web.history.entity.HistoryEvent;
import edu.campusloop.web.history.mapper.*;
import edu.campusloop.web.history.vo.HistoryConfirmationView;
import edu.campusloop.web.item.mapper.ItemMapper;
import edu.campusloop.web.upload.service.LocalUploadService;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.user.mapper.UserMapper;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

@Service
public class HistoryConfirmationService {
    private final HistoryMapper history;
    private final HistoryConfirmationMapper confirmations;
    private final ExchangeMapper exchanges;
    private final ExchangeLifecycleMapper locks;
    private final UserMapper users;
    private final ItemMapper items;
    private final ExchangeTransactionExecutor transactions;
    private final ExchangeDatabaseClock clock;
    private final LocalUploadService files;
    private final ObjectMapper json;
    public HistoryConfirmationService(HistoryMapper history,HistoryConfirmationMapper confirmations,ExchangeMapper exchanges,
        ExchangeLifecycleMapper locks,UserMapper users,ItemMapper items,ExchangeTransactionExecutor transactions,
        ExchangeDatabaseClock clock,LocalUploadService files,ObjectMapper json) {
        this.history=history;this.confirmations=confirmations;this.exchanges=exchanges;this.locks=locks;this.users=users;
        this.items=items;this.transactions=transactions;this.clock=clock;this.files=files;this.json=json;
    }
    public void act(long actor,long item,long id,String action,HistoryConfirmationCommand command) {
        transactions.execute("履历独立确认",()-> {
            HistoryEvent row=history.find(id);
            if(row==null || row.getItemId()!=item) throw new ApiException(404,"履历不存在或不可见");
            if(row.getExchangeId()==null) throw conflict();
            var exchange=locks.lock(row.getExchangeId());
            var people=proof(row);
            if(people.stream().noneMatch(p->p.getUserId()==actor)) throw forbidden();
            for(long user:people.stream().map(ExchangeParticipantRecord::getUserId).sorted().toList()) {
                var current=users.selectByIdForUpdate(user);
                if(current==null || user==actor && !"ACTIVE".equals(current.getStatus())) throw forbidden();
            }
            if(exchange==null || items.selectForUpdate(item)==null) throw conflict();
            // A distinct locking query bypasses the pre-lock MyBatis first-level cache.
            row=history.findCurrent(id);
            if(!"SELF_REPORTED".equals(row.getEvidenceLevel()) || !Set.of("REPAIR","TRANSFER").contains(row.getEventType()) || row.getOwnershipEndedAt()==null) throw conflict();
            var request=confirmations.request(id);var withdrawal=confirmations.withdrawal(id);
            if(!action.equals("confirm") && row.getSourceUserId()!=actor) throw forbidden();
            if(action.equals("withdraw") && request!=null && withdrawal!=null) {
                if(!request.snapshotHash().equals(command.snapshotHash()) || !withdrawal.reason().equals(command.reason())) throw conflict();
                return true;
            }
            if(row.getCorrectedByEventId()!=null || withdrawal!=null) throw conflict();
            var now=clock.now();
            if(action.equals("request")) {
                if(request!=null) return true;
                String snapshot=snapshot(row,people),hash=hash(snapshot.getBytes(StandardCharsets.UTF_8));
                confirmations.appendRequest(new HistoryConfirmationMapper.Request(id,row.getExchangeId(),hash,snapshot,now));
                for(var person:people) confirmations.appendMember(id,row.getExchangeId(),person.getUserId());
            } else {
                if(request==null || !request.snapshotHash().equals(command.snapshotHash())) throw conflict();
                var members=confirmations.members(id);
                if(!members.stream().map(HistoryConfirmationMapper.Member::userId).sorted().toList().equals(people.stream().map(ExchangeParticipantRecord::getUserId).sorted().toList())) throw conflict();
                if(action.equals("confirm")) {
                    if(!hash(snapshot(row,people).getBytes(StandardCharsets.UTF_8)).equals(request.snapshotHash())) throw conflict();
                    var member=members.stream().filter(m->m.userId()==actor).findFirst().orElseThrow(HistoryConfirmationService::forbidden);
                    if(member.confirmedAt()==null) confirmations.appendConfirmation(id,actor,request.snapshotHash(),now);
                } else if(action.equals("withdraw")) confirmations.appendWithdrawal(id,request.snapshotHash(),actor,command.reason(),now);
                else throw new IllegalArgumentException("Unknown history action");
            }
            return true;
        });
    }
    /** Projection belongs to the caller's repeatable-read history transaction. */
    public HistoryConfirmationView view(User user,HistoryEvent row) {
        long actor=user==null?-1:user.getId();boolean admin=user!=null && "ADMIN".equals(user.getRole());
        boolean current=row.getCorrectedByEventId()==null;
        if("EXCHANGED".equals(row.getEventType()) && "BOTH_CONFIRMED".equals(row.getEvidenceLevel())) {
            List<ExchangeParticipantRecord> people;
            try {people=proof(row);} catch(ApiException incomplete) {return empty("B04_HANDOFF","UNAVAILABLE",current,List.of());}
            boolean privateAccess=admin || people.stream().anyMatch(p->p.getUserId()==actor);
            var records=privateAccess?people.stream().map(p->new HistoryConfirmationView.Participant(p.getUserId(),p.getDisplayName(),
                utc(p.getHandedOffAt().isAfter(p.getReceivedAt())?p.getHandedOffAt():p.getReceivedAt()),p.getOfferedItemId(),
                people.stream().filter(q->q.getRecipientUserId().equals(p.getUserId())).findFirst().orElseThrow().getOfferedItemId(),utc(p.getHandedOffAt()),utc(p.getReceivedAt()))).toList():null;
            return new HistoryConfirmationView("B04_HANDOFF","COMPLETE",people.size(),people.size(),current,null,utc(row.getConfirmedAt()),null,null,null,records,null,List.of());
        }
        var request=confirmations.request(row.getId());
        if(request==null) {
            boolean eligible=current && "SELF_REPORTED".equals(row.getEvidenceLevel()) && row.getOwnershipEndedAt()!=null && row.getSourceUserId()==actor;
            if(eligible) {try {proof(row);}catch(ApiException invalid){eligible=false;}}
            return empty("SELF_REPORT",current?"NOT_REQUESTED":"SUPERSEDED",current,eligible?List.of("REQUEST_CONFIRMATION"):List.of());
        }
        var members=confirmations.members(row.getId());var withdrawal=confirmations.withdrawal(row.getId());
        int count=(int)members.stream().filter(m->m.confirmedAt()!=null).count();
        boolean complete=members.size()>=2 && members.size()<=3 && count==members.size();
        var completed=complete?members.stream().map(HistoryConfirmationMapper.Member::confirmedAt).max(Comparator.naturalOrder()).orElseThrow():null;
        boolean privateAccess=admin || row.getSourceUserId()==actor || members.stream().anyMatch(m->m.userId()==actor);
        List<String> actions=new ArrayList<>();
        if(current && withdrawal==null) {
            if(members.stream().anyMatch(m->m.userId()==actor && m.confirmedAt()==null)) actions.add("CONFIRM");
            if(row.getSourceUserId()==actor) actions.add("WITHDRAW_CONFIRMATION");
        }
        return new HistoryConfirmationView("SELF_REPORT",!current?"SUPERSEDED":withdrawal!=null?"WITHDRAWN":complete?"COMPLETE":"PENDING",
            count,members.size(),current,utc(request.requestedAt()),utc(completed),withdrawal==null?null:utc(withdrawal.withdrawnAt()),
            privateAccess?request.snapshotHash():null,privateAccess?parse(request.snapshotJson()):null,
            privateAccess?members.stream().map(m->new HistoryConfirmationView.Participant(m.userId(),m.displayName(),utc(m.confirmedAt()),null,null,null,null)).toList():null,
            privateAccess && withdrawal!=null?withdrawal.reason():null,List.copyOf(actions));
    }
    private List<ExchangeParticipantRecord> proof(HistoryEvent row) {
        if(row.getExchangeId()==null || history.transfer(row.getItemId(),row.getExchangeId())==null) throw conflict();
        var exchange=exchanges.selectById(row.getExchangeId());
        if(exchange==null || !"COMPLETED".equals(exchange.getStatus()) || !ExchangeLifecycleFacts.supported(exchange)) throw conflict();
        var people=exchanges.participants(List.of(exchange.getId()));
        ExchangeLifecycleFacts.snapshot(exchange,people);
        if(people.size()<2 || people.size()>3 || people.stream().anyMatch(p->p.getConfirmedAt()==null || p.getHandedOffAt()==null || p.getReceivedAt()==null)) throw conflict();
        return people.stream().sorted(Comparator.comparing(ExchangeParticipantRecord::getUserId)).toList();
    }
    private String snapshot(HistoryEvent row,List<ExchangeParticipantRecord> people) {
        var value=json.createObjectNode();value.put("ruleVersion","history-confirmation-v1");value.put("eventId",row.getId());value.put("itemId",row.getItemId());
        value.put("exchangeHistoryEventId",history.transfer(row.getItemId(),row.getExchangeId()).getId());
        value.put("authorId",row.getSourceUserId());value.put("exchangeId",row.getExchangeId());value.put("eventType",row.getEventType());value.put("statement",row.getDescription());
        value.put("recordedEvidenceLevel",row.getEvidenceLevel());value.put("occurredAt",row.getOccurredAt()==null?null:utc(row.getOccurredAt()).toString());value.put("recordedAt",utc(row.getRecordedAt()).toString());
        value.put("correctsEventId",row.getCorrectsEventId());
        var roster=value.putArray("participantIds");people.forEach(p->roster.add(p.getUserId()));
        var evidence=value.putArray("evidence");
        for(String id:history.evidenceIds(row.getId())) {
            try {
                var path=files.evidencePath(id);
                if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw conflict();
                evidence.addObject().put("uploadId",id).put("mediaType","image/png").put("sha256",hash(Files.readAllBytes(path)));
            } catch(java.io.IOException missing) {throw conflict();}
        }
        return value.toString();
    }
    private JsonNode parse(String value) {try{return json.readTree(value);}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw conflict();}}
    private static String hash(byte[] value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static HistoryConfirmationView empty(String mode,String status,boolean current,List<String> actions) {return new HistoryConfirmationView(mode,status,0,0,current,null,null,null,null,null,null,null,actions);}
    private static Instant utc(LocalDateTime time) {return time==null?null:time.toInstant(ZoneOffset.UTC);}
    private static ApiException conflict() {return new ApiException(409,"履历内容、确认快照或来源依据已变化，请刷新");}
    private static ApiException forbidden() {return new ApiException(403,"仅该事件真实参与者可本人确认，仅作者可发起或撤回");}
}
