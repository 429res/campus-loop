package edu.campusloop.web.exchange.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

/** The timer only wakes the DB scanner; it is never the durable source of pending work. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(prefix="campus.exchange-expiry",name="enabled",havingValue="true",matchIfMissing=true)
@ConditionalOnExpression("!${campus.bootstrap-only:false}")
public class ExchangeExpiryScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(ExchangeExpiryScheduler.class);
    private final ExchangeExpiryScanner scanner;
    public ExchangeExpiryScheduler(ExchangeExpiryScanner scanner) { this.scanner=scanner; }
    @Scheduled(fixedDelayString="${campus.exchange-expiry.delay-ms:30000}",
        initialDelayString="${campus.exchange-expiry.initial-delay-ms:1000}")
    public void poll() {
        try {
            var result=scanner.scanBatch();
            if(result.deferred()>0 || result.retryWriteFailures()>0)
                LOG.warn("Exchange expiry batch: selected={}, expired={}, deferred={}, retryWriteFailures={}",
                    result.selected(),result.expired(),result.deferred(),result.retryWriteFailures());
            else if(result.expired()>0) LOG.info("Exchange expiry batch: expired={}",result.expired());
        } catch(RuntimeException failure) {
            LOG.warn("Exchange expiry scan failed; database work remains eligible for a later poll");
        }
    }
}
