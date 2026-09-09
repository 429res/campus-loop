package edu.campusloop.web.exchange.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.campusloop.common.*;
import edu.campusloop.web.exchange.entity.*;
import edu.campusloop.web.exchange.mapper.ExchangeMapper;
import edu.campusloop.web.exchange.vo.ExchangeView;
import edu.campusloop.web.exchange.vo.AdminExchangeDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class ExchangeQueryService {
    public static final Set<String> STATUSES=Set.of("AWAITING_CONFIRMATION","READY","COMPLETED","CANCELLED","EXPIRED","DISPUTED");
    private final ExchangeMapper exchanges;
    private final edu.campusloop.web.item.mapper.ItemMapper items;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final ExchangeDatabaseClock clock;
    private final AdminExchangeSnapshotReader creationSnapshots;
    private final edu.campusloop.exchange.ExchangeLifecycleRules rules=new edu.campusloop.exchange.ExchangeLifecycleRules();
    public ExchangeQueryService(ExchangeMapper exchanges,ExchangeDatabaseClock clock,AdminExchangeSnapshotReader creationSnapshots,edu.campusloop.web.item.mapper.ItemMapper items,com.fasterxml.jackson.databind.ObjectMapper json) {
        this.exchanges=exchanges;this.clock=clock;this.creationSnapshots=creationSnapshots;this.items=items;this.json=json;
    }

    public PageResult<ExchangeView> mine(long userId,int page,int size,String status) {
        return page(visible(userId),userId,page,size,status);
    }
    public PageResult<ExchangeView> adminPage(int page,int size,String status) {
        return page(new QueryWrapper<>(),null,page,size,status);
    }
    public AdminExchangeDetail adminDetail(long id) {
        if (id<1) throw new ApiException(400,"交换ID须为正整数");
        ExchangeRecord found=exchanges.selectById(id);
        if (found==null) throw new ApiException(404,"交换不存在或不可见");
        ExchangeView exchange=views(List.of(found),null).get(0);
        var events=exchanges.events(id).stream().map(event -> new AdminExchangeDetail.Event(event.getId(),event.getEventType(),
            event.getActorId(),event.getActorDisplayName(),event.getPreviousStatus(),event.getNewStatus(),
            event.getPreviousVersion(),event.getNewVersion(),event.getReason(),utc(event.getOccurredAt()))).toList();
        return new AdminExchangeDetail(exchange,events,creationSnapshots.read(found,exchange));
    }
    private PageResult<ExchangeView> page(QueryWrapper<ExchangeRecord> query,Long userId,int page,int size,String status) {
        if (page<1 || size<1 || size>100 || (status!=null && !STATUSES.contains(status)))
            throw new ApiException(400,"分页或交换状态参数不正确");
        if (status!=null) query.eq("status",status);
        query.orderByDesc("created_at","id");
        Page<ExchangeRecord> result=exchanges.selectPage(new Page<>(page,size),query);
        return new PageResult<>(views(result.getRecords(),userId),result.getTotal(),page,size);
    }
    public ExchangeView detail(long userId,long id) {
        if (id<1) throw new ApiException(400,"交换ID须为正整数");
        ExchangeRecord found=exchanges.selectOne(visible(userId).eq("id",id));
        if (found==null) throw new ApiException(404,"交换不存在或不可见");
        return views(List.of(found),userId).get(0);
    }
    private QueryWrapper<ExchangeRecord> visible(long userId) {
        if (userId<1) throw new ApiException(403,"用户身份无效");
        return new QueryWrapper<ExchangeRecord>().exists("SELECT 1 FROM cl_exchange_participant p " +
            "WHERE p.exchange_id=cl_exchange.id AND p.user_id={0}",userId);
    }
    List<ExchangeView> adminViews(List<ExchangeRecord> rows) { return views(rows,null); }
    private List<ExchangeView> views(List<ExchangeRecord> rows,Long userId) {
        if (rows.isEmpty()) return List.of();
        Map<Long,List<ExchangeParticipantRecord>> grouped=exchanges.participants(rows.stream().map(ExchangeRecord::getId).toList())
            .stream().collect(Collectors.groupingBy(ExchangeParticipantRecord::getExchangeId));
        var itemIds=grouped.values().stream().flatMap(List::stream).map(ExchangeParticipantRecord::getOfferedItemId).collect(Collectors.toSet());
        Map<Long,edu.campusloop.web.item.entity.Item> itemRows=itemIds.isEmpty()?Map.of():items.selectBatchIds(itemIds).stream().collect(Collectors.toMap(edu.campusloop.web.item.entity.Item::getId,i->i));
        Instant now=utc(clock.now());
        return rows.stream().map(row -> view(row,grouped.getOrDefault(row.getId(),List.of()),userId,now,itemRows)).toList();
    }
    private ExchangeView view(ExchangeRecord row,List<ExchangeParticipantRecord> people,Long userId,Instant now,Map<Long,edu.campusloop.web.item.entity.Item> itemRows) {
        if (people.size()<2 || people.size()>3) throw incomplete();
        Map<Long,ExchangeParticipantRecord> byUser=new HashMap<>();
        Map<Long,Long> incoming=new HashMap<>();
        Set<Long> itemIds=new HashSet<>();
        for (var p:people) {
            if (byUser.put(p.getUserId(),p)!=null || !itemIds.add(p.getOfferedItemId()) ||
                p.getUserId().equals(p.getRecipientUserId()) || incoming.put(p.getRecipientUserId(),p.getOfferedItemId())!=null)
                throw incomplete();
        }
        if (!incoming.keySet().equals(byUser.keySet()) || !byUser.containsKey(row.getInitiatorId())) throw incomplete();
        // With two or three distinct users and no self edges, this is exactly one directed cycle.
        List<ExchangeParticipantRecord> ordered=new ArrayList<>();
        var p=people.stream().min(Comparator.comparingLong(ExchangeParticipantRecord::getOfferedItemId)).orElseThrow();
        for (int i=0;i<people.size();i++) { ordered.add(p); p=byUser.get(p.getRecipientUserId()); }
        var facts=ExchangeLifecycleFacts.supported(row)?ExchangeLifecycleFacts.snapshot(row,people):null;
        Map<Long,String[]> contents=contents(row,itemRows);
        return new ExchangeView(row.getId(),row.getInitiatorId(),row.getStatus(),row.getVersion(),
            utc(row.getCreatedAt()),utc(row.getExpiresAt()),ordered.stream().map(person -> new ExchangeView.Participant(
                person.getUserId(),person.getDisplayName(),person.getOfferedItemId(),incoming.get(person.getUserId()),
                person.getConfirmedAt()==null?"PENDING":"CONFIRMED",utc(person.getConfirmedAt()),
                utc(person.getHandedOffAt()),utc(person.getReceivedAt()),userId==null?null:person.getHandedOffNote(),userId==null?null:person.getReceivedNote())).toList(),
            ordered.stream().map(person -> new ExchangeView.Flow(person.getOfferedItemId(),person.getUserId(),person.getRecipientUserId(),contents.getOrDefault(person.getOfferedItemId(),new String[]{"物品 #"+person.getOfferedItemId(),null})[0],contents.getOrDefault(person.getOfferedItemId(),new String[]{"",null})[1])).toList(),
            userId!=null && facts!=null?rules.permittedActions(facts,ExchangeLifecycleFacts.handover(people),userId,now)
                .stream().map(Enum::name).toList():List.of(),
            row.getCancelledBy(),row.getCancellationReason(),utc(row.getCancelledAt()),row.getDisputedBy(),row.getDisputeReason(),utc(row.getDisputedAt()),row.getRuleVersion());
    }
    private Map<Long,String[]> contents(ExchangeRecord row,Map<Long,edu.campusloop.web.item.entity.Item> current) {
        Map<Long,String[]> result=new HashMap<>();
        current.forEach((id,item)->{if(edu.campusloop.web.item.service.ItemVisibility.PUBLIC_STATES.contains(item.getStatus()))result.put(id,new String[]{item.getTitle(),item.getImageUrl()});});
        if(row.getCreationSnapshot()==null)return result;
        try {
            var root=json.readTree(row.getCreationSnapshot());
            for(var flow:root.path("recommendation").path("flows")) {
                long id=flow.path("itemId").asLong();var item=current.get(id);
                String image=item!=null&&edu.campusloop.web.item.service.ItemVisibility.PUBLIC_STATES.contains(item.getStatus())?item.getImageUrl():null;
                result.put(id,new String[]{flow.path("itemTitle").asText("物品 #"+id),image});
            }
            for(var item:root.path("items")) if(result.containsKey(item.path("id").asLong())) result.put(item.path("id").asLong(),new String[]{item.path("title").asText(),item.path("imageUrl").asText()});
        } catch(java.io.IOException invalid){throw incomplete();}
        return result;
    }
    private static Instant utc(LocalDateTime value) { return value==null?null:value.toInstant(ZoneOffset.UTC); }
    private static ApiException incomplete() { return new ApiException(409,"交换记录不完整，请联系维护人员"); }
}
