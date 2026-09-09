package edu.campusloop.ratelimit;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class FixedWindowRateLimiter {
    private final Clock clock;
    private final RateLimitProperties properties;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Object capacityLock = new Object();

    public FixedWindowRateLimiter(Clock clock, RateLimitProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    public Decision acquire(String namespace, String subject, RateLimitProperties.Policy policy) {
        long windowMillis = policy.getWindow().toMillis();
        if (windowMillis < 1_000) throw new IllegalStateException("限流窗口不得短于 1 秒");
        Instant now = clock.instant();
        long started = Math.floorDiv(now.toEpochMilli(), windowMillis) * windowMillis;
        String key = namespace + ':' + subject;
        if (buckets.containsKey(key)) return update(key, policy, now, started, windowMillis);
        synchronized (capacityLock) {
            if (buckets.containsKey(key)) return update(key, policy, now, started, windowMillis);
            if (buckets.size() >= properties.getMaxKeys()) evictExpired(now.toEpochMilli());
            if (buckets.size() >= properties.getMaxKeys()) key = namespace + ":overflow";
            return update(key, policy, now, started, windowMillis);
        }
    }

    private Decision update(String key, RateLimitProperties.Policy policy, Instant now, long started, long windowMillis) {
        AtomicReference<Bucket> selected = new AtomicReference<>();
        buckets.compute(key, (ignored, current) -> {
            Bucket next = current == null || current.startedAtMillis() != started
                ? new Bucket(started, started + windowMillis, 1)
                : new Bucket(started, current.resetAtMillis(), current.count() + 1);
            selected.set(next);
            return next;
        });
        Bucket bucket = selected.get();
        Instant resetAt = Instant.ofEpochMilli(started + windowMillis);
        long retryAfter = Math.max(1, (resetAt.toEpochMilli() - now.toEpochMilli() + 999) / 1_000);
        return new Decision(bucket.count() <= policy.getLimit(), retryAfter, resetAt);
    }

    private void evictExpired(long nowMillis) {
        for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
            if (entry.getValue().resetAtMillis() <= nowMillis
                && buckets.remove(entry.getKey(), entry.getValue())
                && buckets.size() < properties.getMaxKeys()) return;
        }
    }

    private record Bucket(long startedAtMillis, long resetAtMillis, int count) {}
    public record Decision(boolean allowed, long retryAfterSeconds, Instant resetAt) {}
}
