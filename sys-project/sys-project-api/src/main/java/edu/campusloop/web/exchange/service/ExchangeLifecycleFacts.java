package edu.campusloop.web.exchange.service;

import edu.campusloop.common.ApiException;
import edu.campusloop.exchange.ExchangeLifecycleRules.*;
import edu.campusloop.web.exchange.entity.*;
import java.time.ZoneOffset;
import java.util.*;

/** Adapts persisted participant facts for both command authorization and read-side action projection. */
final class ExchangeLifecycleFacts {
    private ExchangeLifecycleFacts() {}
    static boolean supported(ExchangeRecord row) {
        return "independent-v2".equals(row.getRuleVersion()) && row.getRequestDigest()!=null && row.getCreationSnapshot()!=null;
    }
    static Snapshot snapshot(ExchangeRecord row,List<ExchangeParticipantRecord> people) {
        Set<Long> participants=new HashSet<>(),recipients=new HashSet<>(),confirmed=new HashSet<>(),items=new HashSet<>();
        boolean handover=false;
        for(var p:people) {
            if(!participants.add(p.getUserId()) || !recipients.add(p.getRecipientUserId()) || !items.add(p.getOfferedItemId())
                || p.getUserId().equals(p.getRecipientUserId())) throw incomplete();
            if(p.getConfirmedAt()!=null) confirmed.add(p.getUserId());
            handover|=p.getHandedOffAt()!=null || p.getReceivedAt()!=null;
        }
        if(!participants.equals(recipients) || !participants.contains(row.getInitiatorId())) throw incomplete();
        Cancellation cancellation=null;
        if(row.getCancelledBy()!=null || row.getCancellationReason()!=null || row.getCancelledAt()!=null) {
            if(row.getCancelledBy()==null || row.getCancellationReason()==null || row.getCancelledAt()==null) throw incomplete();
            cancellation=new Cancellation(row.getCancelledBy(),row.getCancellationReason(),row.getCancelledAt().toInstant(ZoneOffset.UTC));
        }
        try {
            return new Snapshot(row.getId(),State.valueOf(row.getStatus()),row.getVersion(),row.getExpiresAt().toInstant(ZoneOffset.UTC),
                participants,confirmed,handover,cancellation);
        } catch(IllegalArgumentException | NullPointerException bad) { throw incomplete(); }
    }
    static ApiException incomplete() { return new ApiException(409,"交换记录或占用已变化，请刷新后联系维护人员"); }
}
