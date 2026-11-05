package com.opspulse.common.error;

public class RateLimitedException extends RuntimeException {
    private final long retryAfterMs;

    public RateLimitedException(long retryAfterMs) {
        super("Rate limit exceeded for this API key");
        this.retryAfterMs = retryAfterMs;
    }

    public long retryAfterSeconds() { return Math.max(1, (retryAfterMs + 999) / 1000); }
}
