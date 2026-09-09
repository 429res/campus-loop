package edu.campusloop.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitCoreTest {
    @Test void windowRecoversWithControlledClock() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-08T00:00:00Z"));
        RateLimitProperties properties = properties();
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(clock, properties);
        RateLimitProperties.Policy policy = new RateLimitProperties.Policy(2, Duration.ofMinutes(1));

        assertTrue(limiter.acquire("login", "client", policy).allowed());
        assertTrue(limiter.acquire("login", "client", policy).allowed());
        FixedWindowRateLimiter.Decision blocked = limiter.acquire("login", "client", policy);
        assertFalse(blocked.allowed());
        assertEquals(60, blocked.retryAfterSeconds());

        clock.advance(Duration.ofSeconds(60));
        assertTrue(limiter.acquire("login", "client", policy).allowed());
    }

    @Test void concurrentRequestsCannotExceedLimit() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-08T00:00:00Z"));
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(clock, properties());
        RateLimitProperties.Policy policy = new RateLimitProperties.Policy(17, Duration.ofMinutes(1));
        var executor = Executors.newFixedThreadPool(16);
        CountDownLatch ready = new CountDownLatch(16), start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 64; i++) results.add(executor.submit(() -> {
                ready.countDown(); start.await();
                return limiter.acquire("upload", "42", policy).allowed();
            }));
            ready.await(); start.countDown();
            int allowed = 0;
            for (Future<Boolean> result : results) if (result.get()) allowed++;
            assertEquals(17, allowed);
        } finally { executor.shutdownNow(); }
    }

    @Test void forwardedHeaderIsIgnoredWithoutTrustedPeerAndWalkedFromRightWhenTrusted() {
        RateLimitProperties direct = properties();
        ClientAddressResolver directResolver = new ClientAddressResolver(direct);
        MockHttpServletRequest untrusted = new MockHttpServletRequest();
        untrusted.setRemoteAddr("203.0.113.7");
        untrusted.addHeader("X-Forwarded-For", "198.51.100.4");
        assertEquals("203.0.113.7", directResolver.resolve(untrusted));

        RateLimitProperties proxied = properties();
        proxied.setTrustedProxies("127.0.0.1/32,10.0.0.0/8");
        ClientAddressResolver proxyResolver = new ClientAddressResolver(proxied);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "192.0.2.44, 198.51.100.8, 10.0.0.2");
        assertEquals("198.51.100.8", proxyResolver.resolve(request));
    }

    private static RateLimitProperties properties() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setMaxKeys(100);
        return properties;
    }

    static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        synchronized void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public synchronized Instant instant() { return instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
