package com.opspulse.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Makes refresh tokens revocable, which a pure JWT is not.
 *
 * Every issued refresh token's jti is stored as refresh:{jti} -> userId with a
 * TTL equal to the token's lifetime. Using a token CONSUMES its entry (GETDEL,
 * atomic), and a new token is issued in its place -- "refresh token rotation".
 *
 * Why that matters: if an attacker steals a refresh token and uses it, the
 * real user's next refresh fails (the jti is gone), which logs them out and
 * surfaces the theft instead of letting both parties share a session forever.
 * Logout is simply deleting the key.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final String PREFIX = "refresh:";
    private final StringRedisTemplate redis;

    public void register(String jti, UUID userId, Duration ttl) {
        redis.opsForValue().set(PREFIX + jti, userId.toString(), ttl);
    }

    /** True exactly once per jti, even under concurrent calls: GETDEL is atomic. */
    public boolean consume(String jti, UUID userId) {
        String owner = redis.opsForValue().getAndDelete(PREFIX + jti);
        return userId.toString().equals(owner);
    }

    public void revoke(String jti) {
        redis.delete(PREFIX + jti);
    }
}
