package edu.campusloop.matching;
import java.util.*;
import java.lang.management.*;
import java.util.function.Supplier;
/** Standalone comparative microbenchmark; latency excludes fixture generation and equality checks. */
public final class MatchingBenchmark {
    private static volatile Object sink;
    private static Object run(Supplier<?> operation) {
        try {return operation.get();}
        catch(IndependentDemandMatcher.MatchingLimitException | ReferenceIndependentDemandMatcher.MatchingLimitException e) {return "422:"+e.getMessage();}
    }
    public static void main(String[] args) {
        int fork=Integer.parseInt(args[0]);
        System.out.println("# java="+System.getProperty("java.version")+",vm="+System.getProperty("java.vm.name")+",os="+System.getProperty("os.name")+",arch="+System.getProperty("os.arch")+",cpus="+Runtime.getRuntime().availableProcessors()+",maxHeap="+Runtime.getRuntime().maxMemory()+",seed="+MatchingBenchmarkData.SEED+",warmup=100,samples=50,fork="+fork);
        System.out.println("fork,distribution,input_items,eligible_items,demands,variant,result_count,outcome,median_ms,p95_ms,allocated_bytes_per_op,peak_heap_bytes");
        for(String distribution:MatchingBenchmarkData.DISTRIBUTIONS) for(int n:new int[]{20,60,120,200}) {
            var input=MatchingBenchmarkData.create(n,distribution,MatchingBenchmarkData.SEED);
            Supplier<?> reference=()->new ReferenceIndependentDemandMatcher().find(input,1);
            Supplier<?> optimized=()->new IndependentDemandMatcher().find(input,1);
            Object expected=run(reference),actual=run(optimized);
            if(!expected.toString().equals(actual.toString())) throw new AssertionError("Output differs: "+distribution+"/"+n);
            for(String variant:fork%2==0?List.of("current","reference"):List.of("reference","current")) {
                Supplier<?> operation=variant.equals("reference")?reference:optimized;
                for(int i=0;i<100;i++) sink=run(operation);
                var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
                boolean allocation=bean.isThreadAllocatedMemorySupported();
                if(allocation && !bean.isThreadAllocatedMemoryEnabled())bean.setThreadAllocatedMemoryEnabled(true);
                var heaps=ManagementFactory.getMemoryPoolMXBeans().stream().filter(p->p.getType()==MemoryType.HEAP).toList();
                heaps.forEach(MemoryPoolMXBean::resetPeakUsage);
                long thread=Thread.currentThread().getId(),bytes=allocation?bean.getThreadAllocatedBytes(thread):0;
                long[] nanos=new long[50];
                for(int i=0;i<nanos.length;i++){long start=System.nanoTime();sink=run(operation);nanos[i]=System.nanoTime()-start;}
                long allocated=allocation?(bean.getThreadAllocatedBytes(thread)-bytes)/50:-1;
                long peak=heaps.stream().mapToLong(p->p.getPeakUsage().getUsed()).sum();Arrays.sort(nanos);
                long eligible=input.offers().stream().filter(o->o.status().equals("AVAILABLE")&&o.userStatus().equals("ACTIVE")&&!o.held()).count();
                System.out.printf(Locale.ROOT,"%d,%s,%d,%d,%d,%s,%d,%s,%.6f,%.6f,%d,%d%n",fork,distribution,n,eligible,input.demands().size(),variant,actual instanceof List<?> list?list.size():-1,actual instanceof List<?>?"OK":"422",(nanos[24]+nanos[25])/2e6,nanos[47]/1e6,allocated,peak);
            }
        }
    }
}
