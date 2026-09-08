package edu.campusloop.web.exchange.service;

import edu.campusloop.common.ApiException;
import edu.campusloop.web.exchange.mapper.ExchangeExpiryMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Bounded stateless polling: deadlines and deferred attempts are entirely persisted in MySQL. */
@Service
public class ExchangeExpiryScanner {
    public record BatchResult(int selected,int expired,int skipped,int deferred,int retryWriteFailures) {}
    private final ExchangeExpiryMapper queue;
    private final ExchangeLifecycleService lifecycle;
    private final ExchangeDatabaseClock clock;
    private final ExchangeTransactionExecutor transactions;
    private final int batchSize;
    public ExchangeExpiryScanner(ExchangeExpiryMapper queue,ExchangeLifecycleService lifecycle,
        ExchangeDatabaseClock clock,ExchangeTransactionExecutor transactions,
        @Value("${campus.exchange-expiry.batch-size:50}") int batchSize) {
        if(batchSize<1 || batchSize>100) throw new IllegalArgumentException("Exchange expiry batch-size must be 1–100");
        this.queue=queue;this.lifecycle=lifecycle;this.clock=clock;this.transactions=transactions;this.batchSize=batchSize;
    }
    public BatchResult scanBatch() {
        var ids=queue.due(clock.now(),batchSize);
        int expired=0,skipped=0,deferred=0,retryWriteFailures=0;
        for(long id:ids) {
            try {
                if(lifecycle.expireForScan(id)) expired++; else skipped++;
            } catch(RuntimeException failure) {
                // Never persist or log exception text, SQL, request values or credentials.
                String code=failure instanceof ApiException?"STATE_CONFLICT":"PROCESSING_FAILED";
                try {
                    int changed=transactions.execute("到期重试记录",()->queue.defer(id,clock.now(),code));
                    if(changed==1) deferred++; else skipped++;
                } catch(RuntimeException retryFailure) {
                    retryWriteFailures++; // Still due in DB; retry on a later poll, including after restart.
                }
            }
        }
        return new BatchResult(ids.size(),expired,skipped,deferred,retryWriteFailures);
    }
}
