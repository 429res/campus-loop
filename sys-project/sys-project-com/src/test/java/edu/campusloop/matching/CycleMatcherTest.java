package edu.campusloop.matching;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CycleMatcherTest {
    private final CycleMatcher matcher=new CycleMatcher();
    private CycleMatcher.Offer offer(long id,long owner,long category,long wanted,String status){
        return new CycleMatcher.Offer(id,owner,"同学"+owner,"物品"+id,category,"分类"+category,wanted,Set.of("  Campus "),Set.of("campus"),status);
    }
    @Test void findsCorrectTwoWayFlowAndExplainsTags(){
        var result=matcher.find(List.of(offer(1,1,1,2,"AVAILABLE"),offer(2,2,2,1,"AVAILABLE")));
        assertEquals(1,result.size());assertEquals(2,result.get(0).length());assertEquals(70,result.get(0).score());
        assertEquals(1,result.get(0).flows().get(0).fromUserId());assertEquals(2,result.get(0).flows().get(0).toUserId());
        assertTrue(result.get(0).flows().get(0).reason().contains("campus"));
    }
    @Test void findsThreeWayWithCorrectDirection(){
        var result=matcher.find(List.of(offer(1,1,1,3,"AVAILABLE"),offer(2,2,2,1,"AVAILABLE"),offer(3,3,3,2,"AVAILABLE")));
        assertEquals(1,result.size());assertEquals(3,result.get(0).length());
        assertEquals(List.of(2L,3L,1L),result.get(0).flows().stream().map(CycleMatcher.Flow::toUserId).toList());
    }
    @Test void rotationAndInputOrderDoNotDuplicateCycles(){
        var offers=List.of(offer(1,1,1,3,"AVAILABLE"),offer(2,2,2,1,"AVAILABLE"),offer(3,3,3,2,"AVAILABLE"));
        var reversed=new ArrayList<>(offers);Collections.reverse(reversed);reversed.add(offers.get(0));
        assertEquals(matcher.find(offers),matcher.find(reversed));assertEquals(1,matcher.find(reversed).size());
    }
    @Test void excludesSameOwnerInTwoAndThreeWay(){
        assertTrue(matcher.find(List.of(offer(1,1,1,2,"AVAILABLE"),offer(2,1,2,1,"AVAILABLE"))).isEmpty());
        assertTrue(matcher.find(List.of(offer(1,1,1,3,"AVAILABLE"),offer(2,2,2,1,"AVAILABLE"),offer(3,1,3,2,"AVAILABLE"))).isEmpty());
    }
    @Test void excludesRepeatedAndConflictingItemIds(){
        assertTrue(matcher.find(List.of(offer(1,1,1,2,"AVAILABLE"),offer(1,2,2,1,"AVAILABLE"))).isEmpty());
    }
    @Test void excludesUnavailableItems(){
        for(String status:List.of("RESERVED","EXCHANGED","HIDDEN","DRAFT","PENDING_REVIEW"))
            assertTrue(matcher.find(List.of(offer(1,1,1,2,"AVAILABLE"),offer(2,2,2,1,status))).isEmpty());
    }
    @Test void emptyAndUnmatchedInputReturnEmpty(){
        assertTrue(matcher.find(List.of()).isEmpty());
        assertTrue(matcher.find(List.of(offer(1,1,1,3,"AVAILABLE"),offer(2,2,2,3,"AVAILABLE"))).isEmpty());
    }
    @Test void directedReverseRingIsDistinctAndTagsOnlyRank(){
        var result=matcher.find(List.of(offer(1,1,1,1,"AVAILABLE"),offer(2,2,1,1,"AVAILABLE"),offer(3,3,1,1,"AVAILABLE")));
        assertEquals(3,result.stream().filter(r->r.length()==2).count());
        assertEquals(2,result.stream().filter(r->r.length()==3).count());
        var noTags=new CycleMatcher.Offer(2,2,"同学2","物品2",2,"数码",1,Set.of(),Set.of(),"AVAILABLE");
        assertEquals(1,matcher.find(List.of(offer(1,1,1,2,"AVAILABLE"),noTags)).size());
    }
}
