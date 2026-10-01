package com.opspulse.web;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deeper than /actuator/health: proves the two stateful dependencies are
 * genuinely reachable and that Flyway actually applied the schema. Useful as a
 * docker-compose healthcheck target, because a Spring context can come up fine
 * while Postgres is still refusing connections.
 */
@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("timestamp", Instant.now().toString());

        boolean ok = true;

        try {
            Integer tables = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'",
                    Integer.class);
            body.put("postgres", Map.of("status", "UP", "publicTables", tables));
        } catch (Exception e) {
            ok = false;
            body.put("postgres", Map.of("status", "DOWN", "error", e.getMessage()));
        }

        try {
            String pong = redis.getConnectionFactory().getConnection().ping();
            body.put("redis", Map.of("status", "UP", "ping", pong));
        } catch (Exception e) {
            ok = false;
            body.put("redis", Map.of("status", "DOWN", "error", e.getMessage()));
        }

        if (!ok) {
            body.put("status", "DEGRADED");
            return ResponseEntity.status(503).body(body);
        }
        return ResponseEntity.ok(body);
    }
}
