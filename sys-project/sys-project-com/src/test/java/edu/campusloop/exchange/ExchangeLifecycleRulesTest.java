package edu.campusloop.exchange;

import edu.campusloop.common.ApiException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static edu.campusloop.exchange.ExchangeLifecycleRules.*;
import static org.junit.jupiter.api.Assertions.*;

/** Sequential pure decisions are NOT MySQL lock/concurrency or API creation evidence. */
class ExchangeLifecycleRulesTest {
    private final ExchangeLifecycleRules rules=new ExchangeLifecycleRules();
    private final Instant deadline=Instant.parse("2026-09-09T01:00:00Z");
    private final Instant before=deadline.minusNanos(1);
    private Snapshot waiting(int size) {
        return new Snapshot(91,State.AWAITING_CONFIRMATION,0,deadline,size==2?Set.of(101L,102L):Set.of(101L,102L,103L),Set.of(),false,null);
    }
    private Snapshot ready() {
        return new Snapshot(91,State.READY,2,deadline,Set.of(101L,102L),Set.of(101L,102L),false,null);
    }
    private void rejected(int code,Runnable op) { assertEquals(code,assertThrows(ApiException.class,op::run).getStatus()); }
    private void unchanged(Snapshot before,Decision result) {
        assertSame(before,result.next()); assertFalse(result.changed());assertNull(result.event());assertNull(result.releaseExchangeId());
    }
    @Test void twoPeopleConfirmIndividuallyAndOnlyLastConfirmationMakesReady() {
        var first=rules.confirm(waiting(2),101,0,before);
        assertEquals(State.AWAITING_CONFIRMATION,first.next().state());assertEquals(Set.of(101L),first.next().confirmed());
        var second=rules.confirm(first.next(),102,1,before);
        assertEquals(State.READY,second.next().state());assertEquals(2,second.next().version());
        assertEquals(deadline,second.next().expiresAt());assertNull(second.releaseExchangeId());
    }
    @Test void threePeopleCannotBecomeReadyWithOnlyTwoConfirmations() {
        var a=rules.confirm(waiting(3),103,0,before);var b=rules.confirm(a.next(),101,1,before);
        assertEquals(State.AWAITING_CONFIRMATION,b.next().state());assertEquals(2,b.next().confirmed().size());
        var c=rules.confirm(b.next(),102,2,before);assertEquals(State.READY,c.next().state());assertEquals(3,c.next().version());
        assertEquals(EventType.CONFIRMED,c.event().type());assertEquals(102L,c.event().actorId());assertEquals(before,c.event().at());
    }
    @Test void repeatedConfirmationDoesNotRepeatEventOrVersionEvenWithEarlierRequestVersion() {
        var current=rules.confirm(waiting(2),101,0,before).next();
        unchanged(current,rules.confirm(current,101,0,before));
        var all=rules.confirm(current,102,1,before).next();unchanged(all,rules.confirm(all,101,0,before));
        rejected(409,()->rules.confirm(all,101,3,before));
    }
    @Test void anyParticipantMayCancelWaitingOrReadyBeforeHandoverAndDeadline() {
        for(var current:List.of(waiting(2),ready())) for(long actor:current.participants()) {
            var result=rules.cancel(current,actor,current.version(),"  时间安排变化  ",before);
            assertEquals(State.CANCELLED,result.next().state());assertEquals(actor,result.next().cancellation().actorId());
            assertEquals("时间安排变化",result.event().reason());assertEquals(before,result.next().cancellation().at());
            assertEquals(91L,result.releaseExchangeId());assertEquals(current.version()+1,result.next().version());
            assertEquals(current.confirmed(),result.next().confirmed());assertEquals(deadline,result.next().expiresAt());
        }
    }
    @Test void exactCancellationReplayAfterDeadlineDoesNotReleaseOrRecordAgain() {
        var cancelled=rules.cancel(ready(),102,2,"计划变化",before).next();
        unchanged(cancelled,rules.cancel(cancelled,102,2," 计划变化 ",deadline.plusSeconds(500)));
        rejected(409,()->rules.cancel(cancelled,101,2,"计划变化",before));
        rejected(409,()->rules.cancel(cancelled,102,2,"新的理由",before));
        rejected(409,()->rules.cancel(cancelled,102,4,"计划变化",before));
        rejected(409,()->rules.confirm(cancelled,102,2,before));
    }
    @Test void deadlineEqualityAndAfterRejectNewUserActionsAndPermitExpiration() {
        for(var current:List.of(waiting(2),ready())) for(var now:List.of(deadline,deadline.plusNanos(1))) {
            rejected(409,()->rules.confirm(current,101,current.version(),now));
            rejected(409,()->rules.cancel(current,101,current.version(),"计划变化",now));
            assertTrue(rules.permittedActions(current,101,now).isEmpty());
            var expired=rules.expire(current,now);assertEquals(State.EXPIRED,expired.next().state());
            assertEquals(91L,expired.releaseExchangeId());assertEquals(now,expired.event().at());assertNull(expired.event().actorId());
        }
    }
    @Test void expirationBeforeDeadlineAndRepeatAfterExpiryAreNoOps() {
        var current=waiting(2);unchanged(current,rules.expire(current,before));
        var expired=rules.expire(current,deadline).next();unchanged(expired,rules.expire(expired,deadline.plusSeconds(1)));
        rejected(409,()->rules.confirm(expired,101,1,before));rejected(409,()->rules.cancel(expired,101,1,"原因",before));
    }
    @Test void confirmedParticipantCannotReplayConfirmationAtOrAfterDeadline() {
        var current=rules.confirm(waiting(2),101,0,before).next();
        rejected(409,()->rules.confirm(current,101,0,deadline));
        assertEquals(Set.of(101L),current.confirmed());assertEquals(1,current.version());
    }
    @Test void allTerminalStatesRejectWritesAndCannotBeExpiredOrReleasedAgain() {
        for(State state:List.of(State.CANCELLED,State.COMPLETED,State.EXPIRED,State.DISPUTED)) {
            var current=new Snapshot(91,state,5,deadline,Set.of(101L,102L),Set.of(101L),false,null);
            rejected(409,()->rules.confirm(current,101,5,before));rejected(409,()->rules.cancel(current,101,5,"原因",before));
            unchanged(current,rules.expire(current,deadline));assertTrue(rules.permittedActions(current,101,before).isEmpty());
        }
    }
    @Test void nonParticipantHasNoAdminOverrideOrExistenceLeak() {
        var current=waiting(2);assertTrue(rules.permittedActions(current,999,before).isEmpty());
        rejected(404,()->rules.confirm(current,999,0,before));rejected(404,()->rules.cancel(current,999,0,"原因",before));
    }
    @Test void handoverEvidenceBlocksConfirmationCancellationAndAutomaticRelease() {
        for(State state:List.of(State.AWAITING_CONFIRMATION,State.READY)) {
            var current=new Snapshot(91,state,2,deadline,Set.of(101L,102L),state==State.READY?Set.of(101L,102L):Set.of(101L),true,null);
            rejected(409,()->rules.cancel(current,101,2,"原因",before));rejected(409,()->rules.confirm(current,101,2,before));
            unchanged(current,rules.expire(current,deadline));assertTrue(rules.permittedActions(current,101,before).isEmpty());
        }
    }
    @Test void confirmThenCancelWithSameVersionMustRefreshAndCannotOverwrite() {
        var start=rules.confirm(waiting(2),101,0,before).next();
        var confirmed=rules.confirm(start,102,1,before).next();
        rejected(409,()->rules.cancel(confirmed,101,1,"原因",before));
        assertEquals(State.READY,confirmed.state());
        assertEquals(State.CANCELLED,rules.cancel(confirmed,101,2,"原因",before).next().state());
    }
    @Test void cancelThenConfirmationCannotRestoreReadyEvenAfterRefresh() {
        var start=rules.confirm(waiting(2),101,0,before).next();var cancelled=rules.cancel(start,102,1,"原因",before).next();
        rejected(409,()->rules.confirm(cancelled,102,1,before));rejected(409,()->rules.confirm(cancelled,102,2,before));
        unchanged(cancelled,rules.expire(cancelled,deadline));
    }
    @Test void expirationAndCancellationSequenceHasOneTerminalDecision() {
        var current=waiting(2);var expired=rules.expire(current,deadline).next();
        rejected(409,()->rules.cancel(expired,101,1,"原因",deadline));
        var cancelled=rules.cancel(current,101,0,"原因",before).next();unchanged(cancelled,rules.expire(cancelled,deadline));
    }
    @Test void permittedActionsUseSameAuthorizationStateTimeAndHandoverPolicyAsCommands() {
        var current=waiting(2);
        assertEquals(List.of(Action.CONFIRM,Action.CANCEL),rules.permittedActions(current,101,before));
        var confirmed=rules.confirm(current,101,0,before).next();assertEquals(List.of(Action.CANCEL),rules.permittedActions(confirmed,101,before));
        assertEquals(List.of(Action.CONFIRM,Action.CANCEL),rules.permittedActions(confirmed,102,before));
        assertEquals(List.of(Action.CANCEL),rules.permittedActions(ready(),102,before));
        for(var snapshot:List.of(current,confirmed,ready())) for(long actor:snapshot.participants())
            for(Action action:rules.permittedActions(snapshot,actor,before)) {
                var result=action==Action.CONFIRM?rules.confirm(snapshot,actor,snapshot.version(),before):rules.cancel(snapshot,actor,snapshot.version(),"原因",before);
                assertTrue(result.changed());
            }
    }
    @Test void invalidVersionReasonAndExhaustionCannotProduceEvents() {
        for(Integer version:Arrays.asList(null,-1)) rejected(400,()->rules.confirm(waiting(2),101,version,before));
        for(String reason:Arrays.asList(null,""," ","a".repeat(1001))) rejected(400,()->rules.cancel(waiting(2),101,0,reason,before));
        var maximum=new Snapshot(91,State.AWAITING_CONFIRMATION,Integer.MAX_VALUE,deadline,Set.of(101L,102L),Set.of(),false,null);
        rejected(409,()->rules.confirm(maximum,101,Integer.MAX_VALUE,before));
        rejected(409,()->rules.cancel(maximum,101,Integer.MAX_VALUE,"原因",before));rejected(409,()->rules.expire(maximum,deadline));
        assertTrue(rules.permittedActions(maximum,101,before).isEmpty());
    }
    @Test void invalidDatabaseSnapshotsFailClosedAndInputsRemainImmutable() {
        rejected(409,()->new Snapshot(91,State.READY,0,deadline,Set.of(101L,102L),Set.of(),false,null));
        rejected(409,()->new Snapshot(91,State.AWAITING_CONFIRMATION,0,deadline,Set.of(101L,102L),Set.of(999L),false,null));
        rejected(409,()->new Snapshot(91,State.AWAITING_CONFIRMATION,0,deadline,Set.of(101L,102L),new HashSet<>(Arrays.asList(101L,null)),false,null));
        var participants=new HashSet<>(Set.of(101L,102L));var current=new Snapshot(91,State.AWAITING_CONFIRMATION,0,deadline,participants,Set.of(),false,null);
        participants.clear();rules.confirm(current,101,0,before);
        assertEquals(Set.of(101L,102L),current.participants());assertTrue(current.confirmed().isEmpty());assertEquals(0,current.version());
        assertThrows(UnsupportedOperationException.class,()->current.participants().clear());
    }
}
