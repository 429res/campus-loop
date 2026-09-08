package edu.campusloop.exchange;

import edu.campusloop.common.ApiException;
import edu.campusloop.matching.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExchangeCycleValidatorTest {
    private final ExchangeCycleValidator validator=new ExchangeCycleValidator();
    private IndependentMatchingInput.Offer offer(long id,long owner,long category,int version) {
        return new IndependentMatchingInput.Offer(id,owner,"虚构同学"+owner,"物品"+id,category,"分类"+category,
            Set.of("教材"),"AVAILABLE","ACTIVE",false,version);
    }
    private IndependentMatchingInput.Demand demand(long id,long owner,long category,long offered,int version) {
        return new IndependentMatchingInput.Demand(id,owner,category,Set.of("教材"),Set.of(offered),"ACTIVE",version);
    }
    private IndependentMatchingInput two() {
        return new IndependentMatchingInput(List.of(offer(11,101,1,3),offer(22,102,2,5)),
            List.of(demand(501,101,2,11,6),demand(502,102,1,22,4)));
    }
    private ExchangeCreationCommand.ExpectedFlow flow(long item,int itemVersion,long demand,int demandVersion) {
        return new ExchangeCreationCommand.ExpectedFlow(item,itemVersion,demand,demandVersion);
    }
    private ExchangeCreationCommand command(List<ExchangeCreationCommand.ExpectedFlow> flows) {
        return new ExchangeCreationCommand("independent-v2","fictional-key",flows);
    }
    private ExchangeCreationCommand twoCommand() { return command(List.of(flow(11,3,502,4),flow(22,5,501,6))); }
    private void rejected(int code,Runnable operation) { assertEquals(code,assertThrows(ApiException.class,operation::run).getStatus()); }

    @Test void legalTwoPartyFlowAndExplicitInitialPolicy() {
        var validated=validator.validate(102,twoCommand(),two());
        assertEquals(List.of(101L,102L),validated.recommendation().flows().stream().map(IndependentDemandMatcher.Flow::fromUserId).toList());
        assertEquals(List.of(102L,101L),validated.recommendation().flows().stream().map(IndependentDemandMatcher.Flow::toUserId).toList());
        assertEquals(70,validated.recommendation().score());
        assertEquals(3,validated.recommendation().participants().get(0).itemVersion());
        assertEquals(4,validated.recommendation().flows().get(0).demandVersion());
        assertEquals("AWAITING_CONFIRMATION",ExchangeCreationPolicy.INITIAL_STATUS);
        assertEquals(24,ExchangeCreationPolicy.CONFIRMATION_WINDOW.toHours());
        assertFalse(ExchangeCreationPolicy.INITIATOR_AUTO_CONFIRMS);
    }
    @Test void threePartyDirectionIsReconstructedFromOwnersAndDemandAssociations() {
        var input=new IndependentMatchingInput(List.of(offer(11,101,1,0),offer(22,102,2,0),offer(33,103,3,0)),
            List.of(demand(501,101,3,11,0),demand(502,102,1,22,0),demand(503,103,2,33,0)));
        var cmd=command(List.of(flow(22,0,503,0),flow(33,0,501,0),flow(11,0,502,0)));
        assertEquals("independent-v2:cycle-11-22-33",validator.validate(103,cmd,input).recommendation().id());
        rejected(409,()->validator.validate(101,command(List.of(flow(11,0,503,0),flow(33,0,502,0),flow(22,0,501,0))),input));
    }
    @Test void replayDigestRotatesAllBoundFieldsAndDistinguishesReverseOrChangedPreconditions() {
        var cmd=command(List.of(flow(11,0,502,0),flow(22,0,503,0),flow(33,0,501,0)));
        var rotated=command(List.of(flow(22,0,503,0),flow(33,0,501,0),flow(11,0,502,0)));
        assertEquals(cmd,rotated); assertEquals(cmd.requestDigest(),rotated.requestDigest());
        assertNotEquals(cmd.requestDigest(),command(List.of(flow(11,0,502,0),flow(33,0,501,0),flow(22,0,503,0))).requestDigest());
        assertNotEquals(twoCommand().requestDigest(),command(List.of(flow(11,4,502,4),flow(22,5,501,6))).requestDigest());
        assertNotEquals(twoCommand().requestDigest(),command(List.of(flow(11,3,502,5),flow(22,5,501,6))).requestDigest());
        assertEquals(twoCommand().requestDigest(),new ExchangeCreationCommand("independent-v2","different-key",twoCommand().flows()).requestDigest());
    }
    @Test void staleItemDemandAndExhaustedItemVersionCannotOverwrite() {
        rejected(409,()->validator.validate(101,command(List.of(flow(11,2,502,4),flow(22,5,501,6))),two()));
        rejected(409,()->validator.validate(101,command(List.of(flow(11,3,502,3),flow(22,5,501,6))),two()));
        var input=new IndependentMatchingInput(List.of(offer(11,101,1,Integer.MAX_VALUE),two().offers().get(1)),two().demands());
        rejected(409,()->validator.validate(101,command(List.of(flow(11,Integer.MAX_VALUE,502,4),flow(22,5,501,6))),input));
    }
    @Test void missingDuplicateOrChangedOwnersAreRejected() {
        rejected(403,()->validator.validate(999,twoCommand(),two()));
        rejected(403,()->validator.validate(0,twoCommand(),two()));
        rejected(409,()->validator.validate(101,twoCommand(),new IndependentMatchingInput(List.of(two().offers().get(0)),two().demands())));
        rejected(400,()->validator.validate(101,twoCommand(),new IndependentMatchingInput(List.of(offer(11,101,1,3),offer(22,101,2,5)),two().demands())));
        rejected(409,()->validator.validate(101,twoCommand(),new IndependentMatchingInput(List.of(two().offers().get(0),two().offers().get(0)),two().demands())));
        rejected(409,()->validator.validate(101,twoCommand(),new IndependentMatchingInput(List.of(offer(11,101,1,3),offer(22,103,2,5)),two().demands())));
    }
    @Test void itemUserAndHoldStatesCannotCreateAnEligibleEdge() {
        for(String state:List.of("DRAFT","PENDING_REVIEW","REJECTED","HIDDEN","RESERVED","EXCHANGED")) {
            var changed=new IndependentMatchingInput.Offer(11,101,"叶","书",1,"教材",Set.of(),state,"ACTIVE",false,3);
            rejected(409,()->validator.validate(101,twoCommand(),new IndependentMatchingInput(List.of(changed,two().offers().get(1)),two().demands())));
        }
        for(var changed:List.of(new IndependentMatchingInput.Offer(11,101,"叶","书",1,"教材",Set.of(),"AVAILABLE","DISABLED",false,3),
            new IndependentMatchingInput.Offer(11,101,"叶","书",1,"教材",Set.of(),"AVAILABLE","ACTIVE",true,3)))
            rejected(409,()->validator.validate(101,twoCommand(),new IndependentMatchingInput(List.of(changed,two().offers().get(1)),two().demands())));
    }
    @Test void inactiveDeletedUnlinkedWrongOwnerOrCategoryCannotUseLegacyFallback() {
        for(var changed:List.of(new IndependentMatchingInput.Demand(502,102,1,Set.of(),Set.of(22L),"INACTIVE",4),
            new IndependentMatchingInput.Demand(502,102,1,Set.of(),Set.of(22L),"DELETED",4),
            new IndependentMatchingInput.Demand(502,102,1,Set.of(),Set.of(),"ACTIVE",4),
            demand(502,999,1,22,4),demand(502,102,3,22,4)))
            rejected(409,()->validator.validate(101,twoCommand(),new IndependentMatchingInput(two().offers(),List.of(two().demands().get(0),changed))));
        rejected(409,()->validator.validate(101,twoCommand(),new IndependentMatchingInput(two().offers(),List.of())));
    }
    @Test void changedBestDemandInvalidatesChosenFlowWithoutUnionOrSilentReselection() {
        var better=demand(500,102,1,22,4); // Same tag contribution, smaller ID wins.
        var demands=new ArrayList<>(two().demands()); demands.add(better);
        rejected(409,()->validator.validate(101,twoCommand(),new IndependentMatchingInput(two().offers(),demands)));
        var current=command(List.of(flow(11,3,500,4),flow(22,5,501,6)));
        assertEquals(500,validator.validate(101,current,new IndependentMatchingInput(two().offers(),demands)).recommendation().flows().get(0).demandId());
    }
    @Test void commandRejectsMalformedRingKeyAndUnsupportedRule() {
        for(var flows:List.of(List.of(flow(11,0,1,0)),List.of(flow(11,0,1,0),flow(11,0,2,0)),
            List.of(flow(11,0,1,0),flow(22,0,1,0)),List.of(flow(11,-1,1,0),flow(22,0,2,0)))) rejected(400,()->command(flows));
        rejected(409,()->new ExchangeCreationCommand("legacy-v1","fictional-key",twoCommand().flows()));
        for(String key:List.of("SHORT","", "has spaces", "k".repeat(65), "UPPER-case"))
            rejected(400,()->new ExchangeCreationCommand("independent-v2",key,twoCommand().flows()));
    }
    @Test void validationDoesNotMutateSnapshotOrCanonicalInput() {
        var input=two();var cmd=twoCommand();
        String before=input.toString()+cmd.toString();
        validator.validate(101,cmd,input);
        assertEquals(before,input.toString()+cmd.toString());
        assertThrows(UnsupportedOperationException.class,()->cmd.flows().clear());
        assertThrows(UnsupportedOperationException.class,()->input.offers().clear());
    }
}
