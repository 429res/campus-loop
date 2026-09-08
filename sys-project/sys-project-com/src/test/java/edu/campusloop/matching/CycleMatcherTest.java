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
    @Test void normalizesOnlySharedTagsAndDisplaysThemInStableOrder(){
        Set<String> rawTags=new HashSet<>(Arrays.asList(null,"", "  ","  CAMPUS ","Campus"," BOOK ","unrelated"));
        var provider=taggedOffer(1,1,2,rawTags,null);
        var receiver=taggedOffer(2,2,1,Set.of(),Set.of("campus","  book ","absent"));
        assertEquals(Set.of("book","campus","unrelated"),provider.tags());
        assertEquals(Set.of(),provider.wantedTags());
        assertEquals(Set.of("absent","book","campus"),receiver.wantedTags());
        var result=matcher.find(List.of(receiver,provider));
        assertEquals(1,result.size());assertEquals(70,result.get(0).score());
        assertEquals("满足同学2想要的「分类1」分类；偏好标签：book、campus",result.get(0).flows().get(0).reason());
        assertEquals("满足同学1想要的「分类2」分类；分类匹配，暂无共同偏好标签",result.get(0).flows().get(1).reason());
    }
    @Test void capsTagContributionAtFourPerFlowWithoutHidingMatchingReasons(){
        Set<String> sixTags=tags("a",6);
        var provider=taggedOffer(1,1,2,sixTags,Set.of("b1"));
        var receiver=taggedOffer(2,2,1,Set.of("b1"),sixTags);
        var recommendation=matcher.find(List.of(provider,receiver)).get(0);
        // Six hits contribute four on the first flow; one hit contributes one on the return flow.
        assertEquals(85,recommendation.score());
        assertTrue(recommendation.flows().get(0).reason().endsWith("a1、a2、a3、a4、a5、a6"));
        var maximum=matcher.find(List.of(taggedOffer(1,1,2,sixTags,sixTags),
            taggedOffer(2,2,1,sixTags,sixTags))).get(0);
        assertEquals(100,maximum.score());
    }
    @Test void scoresThreeWayTagTotalsWithTheExistingRoundingFromSixtyToOneHundred(){
        int[] expectedScores={60,63,67,70,73,77,80,83,87,90,93,97,100};
        for(int total=0;total<expectedScores.length;total++){
            int first=Math.min(total,4),second=Math.min(Math.max(total-4,0),4),third=Math.max(total-8,0);
            var result=matcher.find(List.of(taggedOffer(1,1,3,tags("a",4),tags("c",third)),
                taggedOffer(2,2,1,tags("b",4),tags("a",first)),
                taggedOffer(3,3,2,tags("c",4),tags("b",second))));
            assertEquals(1,result.size(),"total hits="+total);
            assertEquals("cycle-1-2-3",result.get(0).id());
            assertEquals(expectedScores[total],result.get(0).score(),"total hits="+total);
        }
    }
    @Test void higherScorePrecedesShorterLengthAndLexicallyEarlierId(){
        var result=matcher.find(List.of(taggedOffer(1,1,2,Set.of(),Set.of()),
            taggedOffer(2,2,1,Set.of(),Set.of()),
            taggedOffer(10,3,5,Set.of("hit"),Set.of("hit")),
            taggedOffer(11,4,3,Set.of("hit"),Set.of("hit")),
            taggedOffer(12,5,4,Set.of("hit"),Set.of("hit"))));
        assertEquals(List.of("cycle-10-11-12","cycle-1-2"),result.stream().map(CycleMatcher.Recommendation::id).toList());
        assertEquals(List.of(70,60),result.stream().map(CycleMatcher.Recommendation::score).toList());
    }
    @Test void equalScoresSortByLengthThenOriginalStringIdAcrossMultipleReverseRings(){
        var offers=List.of(taggedOffer(2,1,1,Set.of(),Set.of()),taggedOffer(10,1,1,Set.of(),Set.of()),
            taggedOffer(11,1,1,Set.of(),Set.of()),taggedOffer(12,1,1,Set.of(),Set.of()));
        var result=matcher.find(offers);
        assertEquals(List.of("cycle-10-11","cycle-10-12","cycle-11-12","cycle-2-10","cycle-2-11","cycle-2-12",
            "cycle-10-11-12","cycle-10-12-11","cycle-2-10-11","cycle-2-10-12","cycle-2-11-10",
            "cycle-2-11-12","cycle-2-12-10","cycle-2-12-11"),result.stream().map(CycleMatcher.Recommendation::id).toList());
        assertTrue(result.stream().allMatch(recommendation->recommendation.score()==60));
        assertEquals(List.of(2L,10L,11L),result.stream().filter(r->r.id().equals("cycle-2-10-11")).findFirst().orElseThrow()
            .flows().stream().map(CycleMatcher.Flow::fromUserId).toList());
        assertEquals(List.of(2L,11L,10L),result.stream().filter(r->r.id().equals("cycle-2-11-10")).findFirst().orElseThrow()
            .flows().stream().map(CycleMatcher.Flow::fromUserId).toList());
        var reordered=new ArrayList<>(offers);Collections.reverse(reordered);reordered.add(offers.get(1));
        assertEquals(result,matcher.find(reordered));
    }
    @Test void conflictingDuplicateStatusDropsTheWholeItemButPreservesUnrelatedCycles(){
        var first=offer(1,1,1,2,"AVAILABLE");
        var second=offer(2,2,2,1,"AVAILABLE");
        var unrelated=List.of(offer(3,3,3,4,"AVAILABLE"),offer(4,4,4,3,"AVAILABLE"));
        var input=new ArrayList<>(unrelated);input.addAll(List.of(first,second,first,offer(1,1,1,2,"RESERVED")));
        assertEquals(matcher.find(unrelated),matcher.find(input));
        Collections.reverse(input);
        assertEquals(matcher.find(unrelated),matcher.find(input));
    }
    @Test void conflictingDuplicatePreferenceDropsTheWholeItemInsteadOfChoosingOneVersion(){
        var first=taggedOffer(1,1,2,Set.of("a"),Set.of("b"));
        var conflicting=taggedOffer(1,1,2,Set.of("a"),Set.of("changed"));
        var second=taggedOffer(2,2,1,Set.of("b"),Set.of("a"));
        assertEquals(1,matcher.find(List.of(first,first,second)).size());
        assertTrue(matcher.find(List.of(first,conflicting,second)).isEmpty());
        assertTrue(matcher.find(List.of(second,conflicting,first)).isEmpty());
    }
    private CycleMatcher.Offer taggedOffer(long id,long category,long wanted,Set<String> tags,Set<String> wantedTags){
        return new CycleMatcher.Offer(id,id,"同学"+id,"物品"+id,category,"分类"+category,wanted,tags,wantedTags,"AVAILABLE");
    }
    private Set<String> tags(String prefix,int count){
        Set<String> result=new HashSet<>();
        for(int i=1;i<=count;i++)result.add(prefix+i);
        return result;
    }
}
