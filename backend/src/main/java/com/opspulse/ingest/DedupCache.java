package com.opspulse.ingest;

import com.opspulse.config.OpsPulseProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * dedup:{key} -> incidentId. A SHORTCUT, never the truth.
 *
 * With a warm pointer, attaching an alert costs one Redis GET plus one UPDATE
 * and one INSERT -- no SELECT to find the incident. The pointer can be stale
 * (incident resolved by a responder on another replica); that is harmless
 * because the attach UPDATE is conditional on status <> 'RESOLVED' and simply
 * matches zero rows, after which we evict and fall back to Postgres.
 *
 * Every method swallows Redis failures: if Redis is down, ingestion must keep
 * working on Postgres alone, just slower. A cache must never become a
 * dependency that can take the pager offline.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DedupCache {

    private static final String PREFIX = "dedup:";
    private final StringRedisTemplate redis;
    private final OpsPulseProperties props;

    public Optional<UUID> get(String dedupKey) {
        try {
            String v = redis.opsForValue().get(PREFIX + dedupKey);
            return v == null ? Optional.empty() : Optional.of(UUID.fromString(v));
        } catch (RuntimeException e) {
            log.warn("Redis unavailable for dedup lookup, falling back to Postgres: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String dedupKey, UUID incidentId) {
        try {
            redis.opsForValue().set(PREFIX + dedupKey, incidentId.toString(), props.getIngest().getDedupCacheTtl());
        } catch (RuntimeException e) {
            log.warn("Could not warm dedup pointer: {}", e.getMessage());
        }
    }

    public void evict(String dedupKey) {
        try {
            redis.delete(PREFIX + dedupKey);
        } catch (RuntimeException e) {
            log.warn("Could not evict dedup pointer: {}", e.getMessage());
        }
    }
}
