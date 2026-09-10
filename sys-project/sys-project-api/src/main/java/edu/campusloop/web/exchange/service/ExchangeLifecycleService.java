package edu.campusloop.web.exchange.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campusloop.common.ApiException;
import edu.campusloop.exchange.ExchangeLifecycleRules;
import edu.campusloop.exchange.ExchangeLifecycleRules.*;
import edu.campusloop.web.demand.mapper.DemandMapper;
import edu.campusloop.web.demand.entity.Demand;
import edu.campusloop.web.exchange.entity.*;
import edu.campusloop.web.exchange.mapper.*;
import edu.campusloop.web.item.entity.Item;
import edu.campusloop.web.item.mapper.ItemMapper;
import edu.campusloop.web.user.mapper.UserMapper;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static edu.campusloop.web.exchange.service.ExchangeLifecycleFacts.*;

/** Shared B-03/B-04/A-04 transaction; every mutation uses the same locks, clock and rollback boundary. */
@Service
public class ExchangeLifecycleService {
    private final ExchangeTransactionExecutor transactions;
    private final ExchangeDatabaseClock clock;
    private final ExchangeLifecycleMapper writes;
    private final ExchangeMapper exchanges;
    private final UserMapper users;
    private final DemandMapper demands;
    private final ItemMapper items;
    private final ObjectMapper json;
    private final ExchangeResolutionMapper resolutions;
    private final edu.campusloop.web.notification.service.NotificationService notifications;
    private final ExchangeLifecycleRules rules=new ExchangeLifecycleRules();
    private enum Operation { CONFIRM, CANCEL, EXPIRE, HANDED_OFF, RECEIVED, DISPUTE }
    public ExchangeLifecycleService(ExchangeTransactionExecutor transactions,ExchangeDatabaseClock clock,ExchangeLifecycleMapper writes,
        ExchangeMapper exchanges,UserMapper users,DemandMapper demands,ItemMapper items,ObjectMapper json,ExchangeResolutionMapper resolutions,edu.campusloop.web.notification.service.NotificationService notifications) {
        this.transactions=transactions;this.clock=clock;this.writes=writes;this.exchanges=exchanges;this.users=users;
        this.demands=demands;this.items=items;this.json=json;this.resolutions=resolutions;this.notifications=notifications;
    }
    public long confirm(long actor,long id,int version) { change(actor,id,version,null,Operation.CONFIRM);return id; }
    public long cancel(long actor,long id,int version,String reason) { change(actor,id,version,reason,Operation.CANCEL);return id; }
    public long handoff(long actor,long id,int version,HandoffKind kind,String note) {
        if(kind==null) throw new ApiException(400,"交接类型不正确");
        change(actor,id,version,ExchangeLifecycleRules.normalizeNote(note),Operation.valueOf(kind.name()));return id;
    }
    public long dispute(long actor,long id,int version,String reason) { change(actor,id,version,reason,Operation.DISPUTE);return id; }
    public boolean expire(long id) { return change(null,id,null,null,Operation.EXPIRE); }
    /** Scanner skips busy exchanges; all state/time/ownership decisions still use the same locked path. */
    public boolean expireForScan(long id) {
        if(id<1) throw new ApiException(400,"交换ID须为正整数");
        return transactions.execute("交换到期",() -> {
            var row=writes.tryLock(id);
            return row!=null && applyLocked(null,id,null,null,Operation.EXPIRE,row);
        });
    }
    private boolean change(Long actor,long id,Integer version,String reason,Operation operation) {
        if(id<1) throw new ApiException(400,"交换ID须为正整数");
        return transactions.execute("交换操作",() -> locked(actor,id,version,reason,operation));
    }
    private boolean locked(Long actor,long id,Integer version,String reason,Operation operation) {
        var row=writes.lock(id);
        if(row==null) { if(operation==Operation.EXPIRE) return false;throw new ApiException(404,"交换不存在或不可见"); }
        return applyLocked(actor,id,version,reason,operation,row);
    }
    private boolean applyLocked(Long actor,long id,Integer version,String reason,Operation operation,ExchangeRecord row) {
        var people=exchanges.participants(List.of(id));
        if(actor!=null && people.stream().noneMatch(p -> p.getUserId().equals(actor))) throw new ApiException(404,"交换不存在或不可见");
        if(!supported(row)) throw new ApiException(409,"旧交换缺少创建依据，仅支持读取");
        var current=snapshot(row,people);
        // Existing exchange first; then the same user -> demand -> item/hold order as A-03.
        for(long userId:new TreeSet<>(current.participants())) {
            var user=users.selectByIdForUpdate(userId);
            if(user==null) throw incomplete();
            if(Objects.equals(actor,userId) && !"ACTIVE".equals(user.getStatus())) throw new ApiException(403,"参与者账号不可用");
        }
        Decision decision=decide(operation,current,people,row,actor,version,reason,clock.now());
        if(!decision.changed()) return false; // Completed retries must not touch released holds or append events.
        var demandIds=writes.demandIds(id);
        if(demandIds.size()!=("direct-v1".equals(row.getRuleVersion())?0:people.size())) throw incomplete();
        Map<Long,Demand> lockedDemands=new HashMap<>();
        for(long demandId:demandIds) {
            var demand=demands.selectForUpdate(demandId);if(demand==null) throw incomplete();lockedDemands.put(demandId,demand);
        }
        var lockedItems=lockOffers(row,people);
        // User/demand/item locks may have waited past the deadline: only this final DB time authorizes a write.
        var now=clock.now();decision=decide(operation,current,people,row,actor,version,reason,now);
        if(!decision.changed()) return false;
        if(decision.releaseExchangeId()!=null && lockedItems.stream().anyMatch(i -> i.getVersion()==Integer.MAX_VALUE)) throw incomplete();
        boolean handoff=operation==Operation.HANDED_OFF || operation==Operation.RECEIVED;
        if(handoff) {
            if(lockedItems.stream().anyMatch(i -> i.getVersion()==Integer.MAX_VALUE)) throw incomplete();
            if(!"direct-v1".equals(row.getRuleVersion()))validateDemands(row,people,lockedItems,lockedDemands);
        }
        if(operation==Operation.HANDED_OFF && writes.handOff(id,actor,decision.event().reason(),now)!=1) throw incomplete();
        if(operation==Operation.RECEIVED && writes.receive(id,actor,decision.event().reason(),now)!=1) throw incomplete();
        if(operation==Operation.DISPUTE) {
            row.setDisputedBy(actor);row.setDisputeReason(decision.event().reason());row.setDisputedAt(now);
        }
        if(operation==Operation.CONFIRM && writes.confirm(id,actor,now)!=1) throw incomplete();
        var cancellation=decision.next().cancellation();
        row.setStatus(decision.next().state().name());
        row.setCancelledBy(cancellation==null?null:cancellation.actorId());
        row.setCancellationReason(cancellation==null?null:cancellation.reason());
        row.setCancelledAt(cancellation==null?null:LocalDateTime.ofInstant(cancellation.at(),ZoneOffset.UTC));
        if(writes.transition(row)!=1) throw incomplete();
        if(writes.event(id,decision.event().actorId(),decision.event().type().name(),current.state().name(),row.getStatus(),
            current.version(),decision.event().reason(),now)!=1) throw incomplete();
        if(decision.next().state()==State.COMPLETED) {
            for(long demandId:demandIds) {
                var demand=lockedDemands.get(demandId);
                if(writes.fulfill(demandId,demand.getOwnerId(),demand.getVersion(),now)!=1) throw incomplete();
            }
            for(var item:lockedItems) {
                var p=people.stream().filter(person -> person.getOfferedItemId().equals(item.getId())).findFirst().orElseThrow();
                if(writes.transfer(item.getId(),p.getUserId(),p.getRecipientUserId(),item.getVersion())!=1
                    || writes.history(item.getId(),id,p.getUserId(),p.getRecipientUserId(),now)!=1
                    || writes.release(item.getId(),id)!=1) throw incomplete();
            }
        } else if(decision.releaseExchangeId()!=null) for(var item:lockedItems) {
            if(writes.release(item.getId(),id)!=1 || writes.restore(item.getId(),item.getOwnerId(),item.getVersion())!=1) throw incomplete();
        }
        for(var person:people) if(!Objects.equals(actor,person.getUserId())) notifications.send(person.getUserId(),"EXCHANGE","交换进度更新",switch(row.getStatus()) {case "READY"->"交换已就绪，请查看交接进度。";case "COMPLETED"->"交换已完成，物品履历已更新。";case "DISPUTED"->"有参与者提出争议，交接已暂停。";case "CANCELLED"->"交换已取消。";case "EXPIRED"->"交换已超时，物品已恢复可交换。";default->"有参与者确认了交换邀请。";},"/pages/exchanges/exchanges?id="+id,"EXCHANGE:"+id+":"+(current.version()+1));
        return true;
    }
    public long resolve(long actor,long id,edu.campusloop.web.exchange.dto.ResolveDisputeRequest request) {
        if(id<1)throw new ApiException(400,"交换ID不正确");
        return transactions.execute("争议处理",()->{
            var row=writes.lock(id);if(row==null)throw new ApiException(404,"交换不存在");
            var people=exchanges.participants(List.of(id));
            var userIds=new TreeSet<Long>();people.forEach(p->userIds.add(p.getUserId()));userIds.add(actor);
            for(long userId:userIds){var user=users.selectByIdForUpdate(userId);if(user==null)throw incomplete();if(userId==actor)edu.campusloop.auth.AdminPermissions.require(user,"EXCHANGES");}
            String reason=request.reason().trim();
            if(reason.isEmpty()||reason.length()>1000||request.version()<0||request.version()==Integer.MAX_VALUE||!Set.of("RESUME","CANCEL").contains(request.decision()))throw new ApiException(400,"争议处理参数不正确");
            var previous=resolutions.previous(id,request.version());
            if(previous!=null){if(previous.actorUserId()==actor&&previous.decision().equals(request.decision())&&previous.reason().equals(reason)&&previous.returnConfirmed()==request.returnConfirmed())return id;throw new ApiException(409,"此版本已有其他处理决定");}
            if(!supported(row)||!"DISPUTED".equals(row.getStatus())||!row.getVersion().equals(request.version()))throw new ApiException(409,"争议状态已更新，请刷新后重试");
            snapshot(row,people);
            if("CANCEL".equals(request.decision())&&!request.returnConfirmed())throw new ApiException(400,"取消前需要确认全部物品已归还原主");
            if("RESUME".equals(request.decision())&&request.returnConfirmed())throw new ApiException(400,"恢复交接不接受归还确认");
            var demandIds=writes.demandIds(id);if(demandIds.size()!=("direct-v1".equals(row.getRuleVersion())?0:people.size()))throw incomplete();
            for(long demandId:demandIds)if(demands.selectForUpdate(demandId)==null)throw incomplete();
            var offers=lockOffers(row,people);if(offers.stream().anyMatch(i->i.getVersion()==Integer.MAX_VALUE))throw incomplete();
            var now=clock.now();boolean cancel="CANCEL".equals(request.decision());String next=cancel?"CANCELLED":"READY";
            var deadline=cancel?row.getExpiresAt():now.plusDays(1);
            if(resolutions.resolve(id,row.getVersion(),next,deadline)!=1)throw incomplete();
            if(cancel){for(var item:offers)if(writes.release(item.getId(),id)!=1||writes.restore(item.getId(),item.getOwnerId(),item.getVersion())!=1)throw incomplete();}
            else if(resolutions.extend(id,deadline)!=offers.size())throw incomplete();
            if(resolutions.append(id,actor,request.decision(),reason,request.returnConfirmed(),row.getVersion(),now)!=1||writes.event(id,actor,cancel?"ADMIN_CANCELLED":"DISPUTE_RESUMED","DISPUTED",next,row.getVersion(),reason,now)!=1)throw incomplete();
            for(var person:people)notifications.send(person.getUserId(),"DISPUTE",cancel?"争议已处理，交换已取消":"争议已处理，可以继续交接",reason,"/pages/exchanges/exchanges?id="+id,"EXCHANGE:"+id+":"+(row.getVersion()+1));
            return id;
        });
    }
    private Decision decide(Operation operation,Snapshot current,List<ExchangeParticipantRecord> people,ExchangeRecord row,Long actor,Integer version,String reason,LocalDateTime now) {
        Instant utc=now.toInstant(ZoneOffset.UTC);
        return switch(operation) {
            case CONFIRM -> rules.confirm(current,actor,version,utc);
            case CANCEL -> rules.cancel(current,actor,version,reason,utc);
            case EXPIRE -> rules.expire(current,utc);
            case HANDED_OFF, RECEIVED -> rules.handoff(current,handover(people),actor,version,HandoffKind.valueOf(operation.name()),reason,utc);
            case DISPUTE -> rules.dispute(current,ExchangeLifecycleFacts.dispute(row),actor,version,reason,utc);
        };
    }
    private void validateDemands(ExchangeRecord row,List<ExchangeParticipantRecord> people,List<Item> offers,
        Map<Long,Demand> locked) {
        try {
            var commands=json.readTree(row.getCreationSnapshot()).path("command");Set<Long> seen=new HashSet<>();
            for(var entry:commands) {
                var id=entry.path("demandId");var version=entry.path("demandVersion");
                if(!id.isIntegralNumber() || !id.canConvertToLong() || !version.isIntegralNumber() || !version.canConvertToInt()) throw incomplete();
                var demand=locked.get(id.longValue());
                var provider=people.stream().filter(p -> p.getOfferedItemId()==entry.path("itemId").longValue()).findFirst().orElseThrow(ExchangeLifecycleFacts::incomplete);
                var receiver=people.stream().filter(p -> p.getUserId().equals(provider.getRecipientUserId())).findFirst().orElseThrow(ExchangeLifecycleFacts::incomplete);
                var item=offers.stream().filter(i -> i.getId().equals(provider.getOfferedItemId())).findFirst().orElseThrow();
                if(demand==null || !seen.add(demand.getId()) || !demand.getOwnerId().equals(receiver.getUserId())
                    || !"ACTIVE".equals(demand.getStatus()) || demand.getVersion()!=version.intValue() || demand.getVersion()==Integer.MAX_VALUE
                    || !demand.getCategoryId().equals(item.getCategoryId())
                    || writes.demandReference(row.getId(),demand.getId(),receiver.getOfferedItemId(),demand.getVersion())!=1
                    || writes.demandAssociation(demand.getId(),receiver.getOfferedItemId())!=1
                    || demands.activeExchangeReferences(demand.getId())!=1) throw incomplete();
            }
            if(!seen.equals(locked.keySet())) throw incomplete();
        } catch(java.io.IOException bad) { throw incomplete(); }
    }
    private List<Item> lockOffers(ExchangeRecord row,List<ExchangeParticipantRecord> people) {
        Map<Long,Integer> versions=new HashMap<>();
        try {
            var commands=json.readTree(row.getCreationSnapshot()).path("command");
            if(!commands.isArray() || commands.size()!=people.size()) throw incomplete();
            for(var entry:commands) {
                var id=entry.path("itemId");var version=entry.path("itemVersion");
                if(!id.isIntegralNumber() || !id.canConvertToLong() || !version.isIntegralNumber() || !version.canConvertToInt()
                    || version.intValue()<0 || version.intValue()==Integer.MAX_VALUE || versions.put(id.longValue(),version.intValue()+1)!=null) throw incomplete();
            }
        } catch(java.io.IOException bad) { throw incomplete(); }
        var ordered=people.stream().sorted(Comparator.comparingLong(ExchangeParticipantRecord::getOfferedItemId)).toList();
        var expected=ordered.stream().map(ExchangeParticipantRecord::getOfferedItemId).toList();
        if(!versions.keySet().equals(new HashSet<>(expected)) || !writes.heldItems(row.getId()).equals(expected)) throw incomplete();
        List<Item> result=new ArrayList<>();
        for(var participant:ordered) {
            var item=items.selectForUpdate(participant.getOfferedItemId());
            if(item==null || !item.getOwnerId().equals(participant.getUserId()) || !"RESERVED".equals(item.getStatus())
                || !item.getVersion().equals(versions.get(item.getId())) || !Objects.equals(writes.holdOwner(item.getId()),row.getId())
                || writes.otherReferences(item.getId(),row.getId())!=0) throw incomplete();
            result.add(item);
        }
        return result;
    }
}
