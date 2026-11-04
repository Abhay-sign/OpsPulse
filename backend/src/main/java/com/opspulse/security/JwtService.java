package com.opspulse.security;

import com.opspulse.config.OpsPulseProperties;
import com.opspulse.domain.entity.UserAccount;
import com.opspulse.domain.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies HMAC-signed JWTs (jjwt picks HS256/384/512 from the
 * secret's length; the key never leaves the server, so symmetric is fine).
 *
 * Two token types, distinguished by a "typ" claim:
 *   access  -- short-lived (15m), carries id/email/role, sent on every request
 *   refresh -- long-lived (30d), carries only id + a unique jti, sent ONLY to
 *              /auth/refresh. Its jti is also stored in Redis so it can be
 *              revoked and rotated (see RefreshTokenStore).
 *
 * The typ check matters: without it, a stolen 30-day refresh token would work
 * as a 30-day access token.
 */
@Component
public class JwtService {

    static final String TYP = "typ";
    static final String ACCESS = "access";
    static final String REFRESH = "refresh";

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtService(OpsPulseProperties props) {
        byte[] secret = props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            // HS256 needs a >=256-bit key; fail at boot, not at first login.
            throw new IllegalStateException("opspulse.jwt.secret must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.accessTtl = props.getJwt().getAccessTokenTtl();
        this.refreshTtl = props.getJwt().getRefreshTokenTtl();
    }

    public String issueAccessToken(UserAccount user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim(TYP, ACCESS)
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    /** Returns the token and its jti; the caller registers the jti in Redis. */
    public IssuedRefreshToken issueRefreshToken(UUID userId) {
        Instant now = Instant.now();
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .subject(userId.toString())
                .id(jti)
                .claim(TYP, REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshTtl)))
                .signWith(key)
                .compact();
        return new IssuedRefreshToken(token, jti, refreshTtl);
    }

    /** Empty if the signature, expiry or type is wrong. Never throws. */
    public Optional<AuthUser> parseAccessToken(String token) {
        return parse(token, ACCESS).map(c -> new AuthUser(
                UUID.fromString(c.getSubject()),
                c.get("email", String.class),
                Role.valueOf(c.get("role", String.class))));
    }

    public Optional<RefreshClaims> parseRefreshToken(String token) {
        return parse(token, REFRESH).map(c -> new RefreshClaims(UUID.fromString(c.getSubject()), c.getId()));
    }

    public long accessTtlSeconds() { return accessTtl.toSeconds(); }

    private Optional<Claims> parse(String token, String expectedType) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return expectedType.equals(claims.get(TYP, String.class)) ? Optional.of(claims) : Optional.empty();
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record IssuedRefreshToken(String token, String jti, Duration ttl) {}
    public record RefreshClaims(UUID userId, String jti) {}
}
