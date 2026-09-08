package edu.campusloop.matching;
import java.util.*;
import static edu.campusloop.matching.IndependentMatchingInput.*;
/** Fixed-seed, synthetic snapshots. No database or user data. */
public final class MatchingBenchmarkData {
    public static final long SEED=20260908L;
    public static final List<String> DISTRIBUTIONS=List.of("sparse","dense","skewed","multi","inactive","empty");
    public static IndependentMatchingInput create(int n,String distribution,long seed) {
        Random random=new Random(seed+n);List<Offer> offers=new ArrayList<>();List<Demand> demands=new ArrayList<>();
        for(int i=1;i<=n;i++) {
            int category=distribution.equals("dense")?1:distribution.equals("skewed") && random.nextInt(10)<8?1:1+random.nextInt(20);
            String status=distribution.equals("inactive") && i%7==0?"UNPUBLISHED":"AVAILABLE";
            offers.add(new Offer(i,i,"User"+i,"Item"+i,category,"Category"+category,Set.of("tag"+(i%5)),status,
                distribution.equals("inactive") && i%11==0?"DISABLED":"ACTIVE",distribution.equals("inactive") && i%13==0,i%4));
            for(int k=0;k<(distribution.equals("multi")?4:1);k++) {
                long wanted=distribution.equals("empty")?999:distribution.equals("dense")?1:
                    distribution.equals("skewed") && random.nextInt(10)<8?1:1+random.nextInt(20);
                demands.add(new Demand(i*10L+k,i,wanted,Set.of("tag"+random.nextInt(5)),Set.of((long)i),
                    distribution.equals("inactive") && i%3==0?"INACTIVE":"ACTIVE",k));
            }
        }
        return new IndependentMatchingInput(offers,demands);
    }
}
