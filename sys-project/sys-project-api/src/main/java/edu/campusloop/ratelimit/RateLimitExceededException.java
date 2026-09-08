package edu.campusloop.ratelimit;

import java.time.Instant;

public class RateLimitExceededException extends RuntimeException {
    private final long retryAfterSeconds;
    private final Instant resetAt;

    public RateLimitExceededException(long retryAfterSeconds, Instant resetAt) {
        super("请求过于频繁，请稍后重试");
        this.retryAfterSeconds = retryAfterSeconds;
        this.resetAt = resetAt;
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
    public Instant getResetAt() { return resetAt; }
}
