package edu.campusloop.web.history.service;
import edu.campusloop.common.*;
import edu.campusloop.web.history.dto.HistoryRequest;
import edu.campusloop.web.history.entity.HistoryEvent;
import edu.campusloop.web.history.mapper.HistoryMapper;
import edu.campusloop.web.history.vo.HistoryView;
import edu.campusloop.web.item.entity.Item;
import edu.campusloop.web.item.mapper.ItemMapper;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.user.mapper.UserMapper;
import edu.campusloop.web.upload.mapper.UploadMapper;
import edu.campusloop.web.upload.service.LocalUploadService;
import edu.campusloop.web.upload.service.UploadReferenceService;
import edu.campusloop.web.exchange.service.ExchangeDatabaseClock;
import edu.campusloop.web.exchange.service.ExchangeTransactionExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

@Service
public class HistoryService {
    private final HistoryMapper history;private final ItemMapper items;private final UserMapper users;private final UploadMapper uploads;
    private final ExchangeDatabaseClock clock;private final ExchangeTransactionExecutor transactions;private final LocalUploadService files;private final UploadReferenceService references;
    public HistoryService(HistoryMapper history,ItemMapper items,UserMapper users,UploadMapper uploads,ExchangeDatabaseClock clock,ExchangeTransactionExecutor transactions,LocalUploadService files,UploadReferenceService references) {
        this.history=history;this.items=items;this.users=users;this.uploads=uploads;this.clock=clock;this.transactions=transactions;this.files=files;this.references=references;
    }
    public long create(long actor,long itemId,HistoryRequest request) {
        if(itemId<1) throw new ApiException(400,"物品ID须为正整数");
        return transactions.execute("履历提交",()-> {
            var user=users.selectByIdForUpdate(actor);
            if(user==null || !"ACTIVE".equals(user.getStatus())) throw new ApiException(403,"作者账号不可用");
            Item item=items.selectForUpdate(itemId);if(item==null) throw invisible();
            var now=clock.now();var row=new HistoryEvent();
            row.setItemId(itemId);row.setSourceUserId(actor);row.setExchangeId(request.relatedExchangeId());
            row.setEventType(request.eventType());row.setDescription(request.statement());row.setEvidenceLevel("SELF_REPORTED");
            row.setRecordedAt(now);row.setOccurredAt(request.occurredAt()==null?null:LocalDateTime.ofInstant(request.occurredAt(),ZoneOffset.UTC));
            row.setCorrectsEventId(request.correctsEventId());
            if(request.correctsEventId()!=null) {
                var original=history.find(request.correctsEventId());
                if(original==null || original.getItemId()!=itemId) throw invisible();
                if(original.getSourceUserId()!=actor || !"SELF_REPORTED".equals(original.getEvidenceLevel())) throw new ApiException(403,"仅原作者可修正本人自述");
                if(original.getCorrectedByEventId()!=null || original.getOwnershipEndedAt()==null) throw conflict();
                if(!Objects.equals(original.getExchangeId(),request.relatedExchangeId())) throw new ApiException(400,"修正不能改变原交换关联");
                row.setOwnershipStartedAt(original.getOwnershipStartedAt());row.setOwnershipEndedAt(original.getOwnershipEndedAt());row.setCounterpartyUserId(original.getCounterpartyUserId());
            } else scope(row,item,actor,request.relatedExchangeId(),now);
            if(row.getOccurredAt()!=null && (row.getOwnershipStartedAt()!=null && row.getOccurredAt().isBefore(row.getOwnershipStartedAt()) || row.getOccurredAt().isAfter(row.getOwnershipEndedAt()) || row.getOccurredAt().isAfter(now)))
                throw new ApiException(400,"发生时间须在本人声明持有期间且不能晚于记录时间；未知时间请明确标记");
            if(row.getOccurredAt()!=null && row.getOccurredAt().isBefore(LocalDateTime.of(1970,1,1,0,0,1)))
                throw new ApiException(400,"发生时间超出数据库支持范围");
            for(String id:new TreeSet<>(request.evidenceUploadIds())) references.privateEvidence(actor,id);
            if(history.append(row)!=1) throw conflict();
            for(String id:request.evidenceUploadIds()) if(history.evidence(row.getId(),id)!=1) throw conflict();
            return row.getId();
        });
    }
    private void scope(HistoryEvent row,Item item,long actor,Long exchange,LocalDateTime now) {
        HistoryEvent latest=history.latestTransfer(item.getId());
        if(exchange!=null) {
            var transfer=history.transfer(item.getId(),exchange);
            if(transfer==null) throw new ApiException(409,"须关联该物品已完成的真实交换");
            if(transfer.getSourceUserId()==actor) {
                var previous=history.previousTransfer(item.getId(),transfer.getId());
                if(previous!=null && previous.getCounterpartyUserId()!=actor) throw conflict();
                row.setOwnershipStartedAt(previous==null?null:previous.getOccurredAt());
                row.setOwnershipEndedAt(transfer.getOccurredAt());row.setCounterpartyUserId(transfer.getCounterpartyUserId());return;
            }
            if(transfer.getCounterpartyUserId()!=actor) throw new ApiException(403,"不能声明他人的持有经历");
            if(item.getOwnerId()!=actor || latest==null || !latest.getId().equals(transfer.getId())) throw new ApiException(409,"曾经所有者须关联本人交出的交换");
            row.setCounterpartyUserId(transfer.getSourceUserId());
        }
        if(item.getOwnerId()!=actor) throw new ApiException(403,"当前非本人所有；曾经所有者须提供已完成交换证明");
        if(latest!=null && latest.getCounterpartyUserId()!=actor) throw conflict();
        row.setOwnershipStartedAt(latest==null?null:latest.getOccurredAt());row.setOwnershipEndedAt(now);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResult<HistoryView> list(User user,long itemId,int page,int size) {
        if(page<1 || size<1 || size>100) throw new ApiException(400,"分页参数不正确");
        Item item=item(itemId);boolean all=allBasic(user,item);long actor=actor(user);
        long total=history.count(itemId,actor,all);if(!all && total==0) throw invisible();
        var rows=history.page(itemId,actor,all,size,((long)page-1)*size);
        return new PageResult<>(rows.stream().map(row->view(user,row)).toList(),total,page,size);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public HistoryView detail(User user,long itemId,long id) {
        Item item=item(itemId);var row=history.find(id);
        if(row==null || row.getItemId()!=itemId || !allBasic(user,item) && history.related(id,actor(user))==0) throw invisible();
        return view(user,row);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Path evidence(User user,String id) {
        var path=files.evidencePath(id);var upload=uploads.selectById(id);
        if(upload==null || !"PRIVATE_EVIDENCE".equals(upload.getVisibility())
            || upload.getOwnerId()!=user.getId().longValue() && history.readableEvidence(id,user.getId(),admin(user))==0
            || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw invisible();
        return path;
    }
    private HistoryView view(User user,HistoryEvent row) {
        boolean privileged=admin(user) || history.related(row.getId(),actor(user))>0;
        var evidence=privileged?history.evidenceIds(row.getId()).stream().map(id->new HistoryView.Evidence(id,"/api/history-evidence/"+id,"image/png")).toList():null;
        boolean correct=actor(user)==row.getSourceUserId() && "SELF_REPORTED".equals(row.getEvidenceLevel())
            && row.getCorrectedByEventId()==null && row.getOwnershipEndedAt()!=null;
        return new HistoryView(row.getId(),row.getItemId(),row.getEventType(),row.getDescription(),row.getEvidenceLevel(),row.getAuthorDisplayName(),
            utc(row.getOccurredAt()),row.getOccurredAt()==null,utc(row.getRecordedAt()),row.getCorrectsEventId(),row.getCorrectedByEventId(),utc(row.getConfirmedAt()),utc(row.getVerifiedAt()),
            privileged?row.getSourceUserId():null,privileged?row.getExchangeId():null,evidence,correct);
    }
    private Item item(long id) {if(id<1) throw new ApiException(400,"物品ID须为正整数");var item=items.selectById(id);if(item==null) throw invisible();return item;}
    private boolean allBasic(User user,Item item) {return Set.of("AVAILABLE","RESERVED","EXCHANGED").contains(item.getStatus()) || actor(user)==item.getOwnerId() || admin(user);}
    private static long actor(User user) {return user==null?-1:user.getId();}
    private static boolean admin(User user) {return user!=null && "ADMIN".equals(user.getRole());}
    private static Instant utc(LocalDateTime value) {return value==null?null:value.toInstant(ZoneOffset.UTC);}
    private static ApiException invisible() {return new ApiException(404,"履历或证据不存在或不可见");}
    private static ApiException conflict() {return new ApiException(409,"履历修正链或所有权记录已变化，请刷新");}
}
