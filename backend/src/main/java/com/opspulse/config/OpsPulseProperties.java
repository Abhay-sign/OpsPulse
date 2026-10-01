package com.opspulse.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.time.Duration;

@Getter @Setter
@ConfigurationProperties(prefix = "opspulse")
@EnableConfigurationProperties(OpsPulseProperties.class)
public class OpsPulseProperties {

    private Jwt jwt = new Jwt();
    private Ingest ingest = new Ingest();
    private Escalation escalation = new Escalation();

    @Getter @Setter
    public static class Jwt {
        private String secret;
        private Duration accessTokenTtl = Duration.ofMinutes(15);
        private Duration refreshTokenTtl = Duration.ofDays(30);
    }

    @Getter @Setter
    public static class Ingest {
        /** Token bucket capacity per API key, per minute. */
        private int rateLimitPerMinute = 600;
        /** How long a dedupKey -> incidentId mapping stays warm in Redis. */
        private Duration dedupCacheTtl = Duration.ofHours(1);
    }

    @Getter @Setter
    public static class Escalation {
        private long sweepIntervalMs = 30_000;
        private int batchSize = 100;
    }
}
