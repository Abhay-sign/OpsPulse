package com.opspulse.ingest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Token bucket per API key, kept in Redis so all replicas share one budget.
 *
 * The whole read-refill-decrement-write runs as ONE Lua script. Redis executes
 * a script atomically, so two replicas can't both read "1 token left" and both
 * spend it -- the classic check-then-act race that a GET followed by a SET
 * would have.
 *
 * The script reads the clock with Redis TIME rather than taking "now" from the
 * caller, so clock skew between app replicas can't mint extra tokens.
 *
 * Fails OPEN: if Redis is down we accept the alert. Dropping real alerts
 * during an outage is worse than briefly losing abuse protection.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>("""
            local capacity = tonumber(ARGV[1])
            local per_ms   = capacity / 60000
            local t   = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local b      = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(b[1]) or capacity
            local ts     = tonumber(b[2]) or now
            tokens = math.min(capacity, tokens + (now - ts) * per_ms)
            local allowed, retry = 0, 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            else
              retry = math.ceil((1 - tokens) / per_ms)
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], 120000)
            return {allowed, retry}
            """, List.class);

    private final StringRedisTemplate redis;

    /** @return 0 if allowed, otherwise milliseconds until a token is available */
    public long tryAcquire(UUID apiKeyId, int perMinute) {
        try {
            List<?> r = redis.execute(SCRIPT, List.of("ratelimit:" + apiKeyId), String.valueOf(perMinute));
            return ((Number) r.get(0)).longValue() == 1 ? 0 : Math.max(1, ((Number) r.get(1)).longValue());
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, allowing request: {}", e.getMessage());
            return 0;
        }
    }
}
