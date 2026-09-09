package edu.campusloop.web.exchange.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campusloop.common.ApiException;
import edu.campusloop.exchange.*;
import edu.campusloop.web.demand.entity.Demand;
import edu.campusloop.web.demand.mapper.DemandMapper;
import edu.campusloop.web.exchange.entity.ExchangeCreationRecord;
import edu.campusloop.web.exchange.mapper.*;
import edu.campusloop.web.item.mapper.ItemMapper;
import edu.campusloop.web.item.service.ItemMutationGuard;
import edu.campusloop.web.user.mapper.UserMapper;
import org.springframework.stereotype.Service;
import java.util.*;

/** The implementation of B's sole port. Every retry starts AFTER the previous transaction rolls back. */
@Service
public class DefaultExchangeCreationTransaction implements ExchangeCreationTransaction {
    private final ExchangeTransactionExecutor transaction;
    private final ExchangeCreationMapper writes;
    private final ExchangeCandidateMapper candidateItems;
    private final ExchangeCandidateReader candidates;
    private final UserMapper users;
    private final DemandMapper demands;
    private final ItemMapper items;
    private final ItemMutationGuard itemGuard;
    private final ObjectMapper json;
    private final ExchangeDatabaseClock clock;
    private final edu.campusloop.web.notification.service.NotificationService notifications;

    public DefaultExchangeCreationTransaction(ExchangeTransactionExecutor transaction, ExchangeCreationMapper writes,
        ExchangeCandidateMapper candidateItems, ExchangeCandidateReader candidates, UserMapper users,
        DemandMapper demands, ItemMapper items, ItemMutationGuard itemGuard, ObjectMapper json, ExchangeDatabaseClock clock,edu.campusloop.web.notification.service.NotificationService notifications) {
        this.transaction=transaction;
        this.writes=writes; this.candidateItems=candidateItems; this.candidates=candidates; this.users=users;
        this.demands=demands; this.items=items; this.itemGuard=itemGuard; this.json=json; this.clock=clock;this.notifications=notifications;
    }

    @Override public long create(long initiatorId, ExchangeCreationCommand command) {
        if (initiatorId<1) throw new ApiException(403,"发起人身份无效");
        return transaction.execute("交换创建",() -> createLocked(initiatorId,command));
    }

    private long createLocked(long initiatorId, ExchangeCreationCommand command) {
        List<Long> ids=command.flows().stream().map(ExchangeCreationCommand.ExpectedFlow::itemId).sorted().toList();
        // Discovery is only a lock hint. Recheck ownership once items are locked, never acquire a late user lock.
        Set<Long> ownerIds=new TreeSet<>();
        candidateItems.items(ids).forEach(i -> ownerIds.add(i.getOwnerId()));
        ownerIds.add(initiatorId);
        Map<Long,String> userStates=new HashMap<>();
        for(long id:ownerIds) {
            var user=users.selectByIdForUpdate(id);
            userStates.put(id,user==null?"MISSING":user.getStatus());
        }
        if (!"ACTIVE".equals(userStates.get(initiatorId))) throw new ApiException(403,"发起人账号不可用");
        // The initiator row serializes SAME key even when the second request names entirely different items.
        var previous=writes.replay(initiatorId,command.idempotencyKey());
        if (previous!=null) {
            if (!command.requestDigest().equals(previous.getRequestDigest()))
                throw new ApiException(409,"幂等键已用于不同请求，请勿复用该键提交其他方案");
            return previous.getId();
        }
        if (userStates.values().stream().anyMatch(s -> !"ACTIVE".equals(s))) throw stale();

        // Demand mutations take their owner user lock before any demand/item lock. This stabilizes ALL
        // demand choices, including new rows and unselected alternatives, without an unbounded gap lock.
        Set<Long> demandIds=new TreeSet<Long>(command.direct()?List.of():writes.associatedDemandIds(ids));
        if (demandIds.size()>20000) throw new ApiException(422,"有效需求关联超过20000条");
        if(!command.direct()) command.flows().forEach(f -> demandIds.add(f.demandId()));
        Map<Long,Demand> lockedDemands=new HashMap<>();
        for(long id:demandIds) {
            var demand=demands.selectForUpdate(id);
            if (demand!=null) lockedDemands.put(id,demand);
        }
        // Completion closes the selected demand. A second live exchange could not complete safely
        // after that closure, even if it offered a different item linked to the same demand.
        for(var flow:command.flows()) {
            if (!command.direct() && demands.activeExchangeReferences(flow.demandId())>0) throw stale();
        }
        for(long id:ids) {
            var item=items.selectForUpdate(id);
            if (item==null || !ownerIds.contains(item.getOwnerId())) throw stale();
            itemGuard.requireUnoccupied(item);
        }
        var snapshot=candidates.snapshot(ids,!command.direct()); // Joins this READ_COMMITTED transaction; all business inputs are locked.
        var validated=new ExchangeCycleValidator().validate(initiatorId,command,snapshot);
        var row=new ExchangeCreationRecord();
        row.setInitiatorId(initiatorId); row.setIdempotencyKey(command.idempotencyKey());
        row.setRequestDigest(command.requestDigest()); row.setRuleVersion(command.ruleVersion());
        row.setStatus(ExchangeCreationPolicy.INITIAL_STATUS);
        row.setCreationSnapshot(encode(Map.of("command",command.flows(),"recommendation",validated.recommendation(),"items",candidateItems.items(ids).stream().map(i->Map.of("id",i.getId(),"title",i.getTitle(),"imageUrl",i.getImageUrl()==null?"":i.getImageUrl())).toList())));
        row.setCreatedAt(clock.now()); row.setExpiresAt(row.getCreatedAt().plus(ExchangeCreationPolicy.CONFIRMATION_WINDOW));
        writes.insert(row);
        var flows=validated.recommendation().flows();
        for(int i=0;i<flows.size();i++) {
            var flow=flows.get(i);
            writes.participant(row.getId(),flow.fromUserId(),flow.itemId(),flow.toUserId());
            if(command.direct())continue;
            Demand demand=lockedDemands.get(flow.demandId());
            long receiverOffer=flows.get((i+1)%flows.size()).itemId();
            writes.demand(row.getId(),demand.getId(),receiverOffer,demand.getVersion(),encode(demand));
        }
        // Use the same ascending item order for holds and conditional state/version updates.
        for(var offer:snapshot.offers().stream().sorted(Comparator.comparingLong(o -> o.id())).toList()) {
            writes.hold(offer.id(),row.getId(),row.getExpiresAt());
            if (writes.reserve(offer.id(),offer.ownerId(),offer.version())!=1) throw stale();
        }
        for(var flow:flows)if(flow.fromUserId()!=initiatorId)notifications.send(flow.fromUserId(),"EXCHANGE","收到新的交换邀请","有同学邀请你参与物品交换，请查看并确认。","/pages/exchanges/exchanges?id="+row.getId(),"EXCHANGE:"+row.getId()+":0");
        return row.getId();
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch(JsonProcessingException exception) { throw new IllegalStateException("Cannot serialize exchange snapshot"); }
    }
    private static ApiException stale() { return new ApiException(409,"物品、需求或推荐已变化，请刷新后重新选择"); }
}
