package edu.campusloop.exchange;

import edu.campusloop.common.ApiException;
import java.time.Instant;
import java.util.*;

/**
 * Shared pure policy for B-03.2 and A-04. Not wired to production mutation endpoints.
 * A future single lifecycle transaction must supply locked database facts and database UTC,
 * atomically apply the decision and audit, and release only the specified exchange's holds.
 */
public final class ExchangeLifecycleRules {
    public enum State { AWAITING_CONFIRMATION, READY, COMPLETED, CANCELLED, EXPIRED, DISPUTED }
    public enum Action { CONFIRM, CANCEL }
    public enum EventType { CONFIRMED, CANCELLED, EXPIRED }
    public record Cancellation(long actorId, String reason, Instant at) {}
    public record Snapshot(long exchangeId, State state, int version, Instant expiresAt,
                           Set<Long> participants, Set<Long> confirmed, boolean handoverStarted,
                           Cancellation cancellation) {
        public Snapshot {
            if (exchangeId<1 || state==null || version<0 || expiresAt==null || participants==null || confirmed==null)
                throw invalidSnapshot();
            if (participants.size()<2 || participants.size()>3 || participants.stream().anyMatch(id -> id==null || id<1)
                || !participants.containsAll(confirmed)) throw invalidSnapshot();
            participants=Set.copyOf(participants); confirmed=Set.copyOf(confirmed);
            if (state==State.READY && confirmed.size()!=participants.size()
                || state==State.AWAITING_CONFIRMATION && confirmed.size()==participants.size()) throw invalidSnapshot();
            if (cancellation!=null && (state!=State.CANCELLED || !participants.contains(cancellation.actorId())
                || cancellation.reason()==null || cancellation.reason().isBlank() || cancellation.at()==null)) throw invalidSnapshot();
        }
    }
    public record Event(EventType type, Long actorId, String reason, Instant at) {}
    /** A null event is a replay/no-op; it MUST NOT insert audit rows or release holds again. */
    public record Decision(Snapshot next, Event event, Long releaseExchangeId) {
        public boolean changed() { return event!=null; }
    }

    public Decision confirm(Snapshot current, long actorId, Integer expectedVersion, Instant databaseNow) {
        requireParticipant(current,actorId); requireVersionInput(current,expectedVersion); requireOpen(current,databaseNow);
        if (current.confirmed().contains(actorId)) return unchanged(current);
        requireCurrentVersion(current,expectedVersion);
        Set<Long> confirmed=new HashSet<>(current.confirmed()); confirmed.add(actorId);
        State next=confirmed.size()==current.participants().size()?State.READY:State.AWAITING_CONFIRMATION;
        return new Decision(new Snapshot(current.exchangeId(),next,current.version()+1,current.expiresAt(),
            current.participants(),confirmed,false,null),new Event(EventType.CONFIRMED,actorId,null,databaseNow),null);
    }

    public Decision cancel(Snapshot current, long actorId, Integer expectedVersion, String reason, Instant databaseNow) {
        requireParticipant(current,actorId); requireVersionInput(current,expectedVersion);
        String normalized=normalizeReason(reason);
        if (current.state()==State.CANCELLED && current.cancellation()!=null
            && current.cancellation().actorId()==actorId && current.cancellation().reason().equals(normalized))
            return unchanged(current); // A successful cancellation retry never extends time or repeats release.
        requireOpen(current,databaseNow); requireCurrentVersion(current,expectedVersion);
        return new Decision(new Snapshot(current.exchangeId(),State.CANCELLED,current.version()+1,current.expiresAt(),
            current.participants(),current.confirmed(),false,new Cancellation(actorId,normalized,databaseNow)),
            new Event(EventType.CANCELLED,actorId,normalized,databaseNow),current.exchangeId());
    }

    /** A-04 internal decision, not a public action or a scheduler. Handover is never automatically undone. */
    public Decision expire(Snapshot current, Instant databaseNow) {
        Objects.requireNonNull(databaseNow,"databaseNow");
        if (!live(current) || current.handoverStarted() || databaseNow.isBefore(current.expiresAt())) return unchanged(current);
        requireCurrentVersion(current,current.version());
        return new Decision(new Snapshot(current.exchangeId(),State.EXPIRED,current.version()+1,current.expiresAt(),
            current.participants(),current.confirmed(),false,null),new Event(EventType.EXPIRED,null,null,databaseNow),current.exchangeId());
    }

    /** Future allowedActions projection. An unavailable transaction capability must still return [] in the API. */
    public List<Action> permittedActions(Snapshot current, long actorId, Instant databaseNow) {
        if (!current.participants().contains(actorId) || current.version()==Integer.MAX_VALUE || !live(current)
            || current.handoverStarted() || !databaseNow.isBefore(current.expiresAt())) return List.of();
        return current.confirmed().contains(actorId)?List.of(Action.CANCEL):List.of(Action.CONFIRM,Action.CANCEL);
    }

    private static Decision unchanged(Snapshot current) { return new Decision(current,null,null); }
    private static boolean live(Snapshot current) { return current.state()==State.AWAITING_CONFIRMATION || current.state()==State.READY; }
    private static void requireOpen(Snapshot current,Instant databaseNow) {
        Objects.requireNonNull(databaseNow,"databaseNow");
        if (!live(current)) throw new ApiException(409,"交换已结束或当前状态不允许此操作");
        if (current.handoverStarted()) throw new ApiException(409,"交接已开始，不能直接确认邀请、取消或释放物品");
        if (!databaseNow.isBefore(current.expiresAt())) throw new ApiException(409,"确认截止时间已到，请刷新交换状态");
    }
    private static void requireParticipant(Snapshot current,long actorId) {
        if (!current.participants().contains(actorId)) throw new ApiException(404,"交换不存在或不可见");
    }
    private static void requireVersionInput(Snapshot current,Integer expectedVersion) {
        if (expectedVersion==null || expectedVersion<0) throw new ApiException(400,"需要非负整数 version");
        if (expectedVersion>current.version()) throw new ApiException(409,"交换版本不正确，请刷新后重试");
    }
    private static void requireCurrentVersion(Snapshot current,int expectedVersion) {
        if (expectedVersion!=current.version() || current.version()==Integer.MAX_VALUE)
            throw new ApiException(409,"交换已更新或版本达到上限，请刷新后重试");
    }
    private static String normalizeReason(String reason) {
        if (reason==null || reason.trim().isEmpty() || reason.trim().length()>1000)
            throw new ApiException(400,"取消原因须为1–1000字");
        return reason.trim();
    }
    private static ApiException invalidSnapshot() { return new ApiException(409,"交换记录不完整，不能计算参与者动作"); }
}
