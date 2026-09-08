package edu.campusloop.matching;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static edu.campusloop.matching.IndependentMatchingInput.*;
class MatchingEquivalenceTest {
    private String reference(IndependentMatchingInput input,long viewer) {
        try{return new ReferenceIndependentDemandMatcher().find(input,viewer).toString();}
        catch(ReferenceIndependentDemandMatcher.MatchingLimitException e){return "422:"+e.getMessage();}
    }
    private String current(IndependentMatchingInput input,long viewer) {
        try{return new IndependentDemandMatcher().find(input,viewer).toString();}
        catch(IndependentDemandMatcher.MatchingLimitException e){return "422:"+e.getMessage();}
    }
    private void equal(IndependentMatchingInput input,long viewer) {
        // Compare every record component, ordered flow, explanation and sorted recommendation.
        assertEquals(reference(input,viewer),current(input,viewer));
    }
    @Test void benchmarkScalesAndDistributionsPreserveCompleteOutputsOrExactLimits() {
        for(String distribution:MatchingBenchmarkData.DISTRIBUTIONS) for(int n:new int[]{20,60,120,200,201}) {
            var input=MatchingBenchmarkData.create(n,distribution,MatchingBenchmarkData.SEED);
            equal(input,1);equal(input,n);equal(input,n+1);
        }
    }
    @Test void randomizedGraphsIncludeMultipleOwnedItemsInvalidAssociationsAndConflictingSnapshots() {
        for(int seed=0;seed<40;seed++) {
            Random random=new Random(seed);List<Offer> offers=new ArrayList<>();List<Demand> demands=new ArrayList<>();
            for(int i=1;i<=18;i++) {
                long owner=1+random.nextInt(9),category=1+random.nextInt(4);
                offers.add(new Offer(i,owner,"U"+owner,"I"+i,category,"C"+category,Set.of(" X ","y"),
                    random.nextInt(5)==0?"UNPUBLISHED":"AVAILABLE","ACTIVE",random.nextInt(7)==0,seed));
                for(int k=0;k<3;k++)demands.add(new Demand(i*10L+k,random.nextInt(6)==0?99:owner,1+random.nextInt(4),
                    Set.of("x","Y"),Set.of((long)i),random.nextInt(5)==0?"INACTIVE":"ACTIVE",k));
                if(i%9==0)offers.add(new Offer(i,owner,"conflict","I"+i,category,"C",Set.of(),"AVAILABLE","ACTIVE",false,seed+1));
            }
            Collections.shuffle(offers,random);Collections.shuffle(demands,random);
            var input=new IndependentMatchingInput(offers,demands);
            for(int viewer=1;viewer<=9;viewer++)equal(input,viewer);
        }
    }
    @Test void versionMetadataAndInactiveChangesRemainVisibleWithoutNewHardRules() {
        var input=MatchingBenchmarkData.create(4,"dense",1);equal(input,1);
        var changed=new IndependentMatchingInput(input.offers(),input.demands().stream().map(d->
            new Demand(d.id(),d.ownerId(),d.categoryId(),d.preferredTags(),d.offeredItemIds(),d.id()==20?"INACTIVE":d.status(),d.version()+1)).toList());
        equal(changed,1);var recommendations=new IndependentDemandMatcher().find(changed,1);
        assertFalse(recommendations.isEmpty());
        assertTrue(recommendations.stream().allMatch(r->r.ruleVersion().equals("independent-v2")));
        assertTrue(recommendations.stream().flatMap(r->r.flows().stream()).allMatch(f->f.demandVersion()==1 && f.demandId()!=20));
        assertNotEquals(current(input,1),current(changed,1));
    }
}
