package edu.campusloop.web.exchange.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campusloop.common.ApiException;
import edu.campusloop.exchange.ExchangeLifecycleRules;
import edu.campusloop.exchange.ExchangeLifecycleRules.*;
import edu.campusloop.web.demand.mapper.DemandMapper;
import edu.campusloop.web.exchange.entity.*;
import edu.campusloop.web.exchange.mapper.*;
import edu.campusloop.web.item.entity.Item;
import edu.campusloop.web.item.mapper.ItemMapper;
import edu.campusloop.web.user.mapper.UserMapper;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static edu.campusloop.web.exchange.service.ExchangeLifecycleFacts.*;

/** Sole confirm/cancel/expire transaction. A-04 calls expire; no scanner or external expiry endpoint here. */
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
    private final ExchangeLifecycleRules rules=new ExchangeLifecycleRules();
    private enum Operation { CONFIRM, CANCEL, EXPIRE }
    public ExchangeLifecycleService(ExchangeTransactionExecutor transactions,ExchangeDatabaseClock clock,ExchangeLifecycleMapper writes,
        ExchangeMapper exchanges,UserMapper users,DemandMapper demands,ItemMapper items,ObjectMapper json) {
        this.transactions=transactions;this.clock=clock;this.writes=writes;this.exchanges=exchanges;this.users=users;
        this.demands=demands;this.items=items;this.json=json;
    }
    public long confirm(long actor,long id,int version) { change(actor,id,version,null,Operation.CONFIRM);return id; }
    public long cancel(long actor,long id,int version,String reason) { change(actor,id,version,reason,Operation.CANCEL);return id; }
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
        Decision decision=decide(operation,current,actor,version,reason,clock.now());
        if(!decision.changed()) return false; // Completed retries must not touch released holds or append events.
        var demandIds=writes.demandIds(id);
        if(demandIds.size()!=people.size()) throw incomplete();
        for(long demandId:demandIds) if(demands.selectForUpdate(demandId)==null) throw incomplete();
        var lockedItems=lockOffers(row,people);
        // User/demand/item locks may have waited past the deadline: only this final DB time authorizes a write.
        var now=clock.now();decision=decide(operation,current,actor,version,reason,now);
        if(!decision.changed()) return false;
        if(decision.releaseExchangeId()!=null && lockedItems.stream().anyMatch(i -> i.getVersion()==Integer.MAX_VALUE)) throw incomplete();
        if(operation==Operation.CONFIRM && writes.confirm(id,actor,now)!=1) throw incomplete();
        var cancellation=decision.next().cancellation();
        row.setStatus(decision.next().state().name());
        row.setCancelledBy(cancellation==null?null:cancellation.actorId());
        row.setCancellationReason(cancellation==null?null:cancellation.reason());
        row.setCancelledAt(cancellation==null?null:LocalDateTime.ofInstant(cancellation.at(),ZoneOffset.UTC));
        if(writes.transition(row)!=1) throw incomplete();
        if(writes.event(id,decision.event().actorId(),decision.event().type().name(),current.state().name(),row.getStatus(),
            current.version(),decision.event().reason(),now)!=1) throw incomplete();
        if(decision.releaseExchangeId()!=null) for(var item:lockedItems) {
            if(writes.release(item.getId(),id)!=1 || writes.restore(item.getId(),item.getOwnerId(),item.getVersion())!=1) throw incomplete();
        }
        return true;
    }
    private Decision decide(Operation operation,Snapshot current,Long actor,Integer version,String reason,LocalDateTime now) {
        Instant utc=now.toInstant(ZoneOffset.UTC);
        return switch(operation) {
            case CONFIRM -> rules.confirm(current,actor,version,utc);
            case CANCEL -> rules.cancel(current,actor,version,reason,utc);
            case EXPIRE -> rules.expire(current,utc);
        };
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
