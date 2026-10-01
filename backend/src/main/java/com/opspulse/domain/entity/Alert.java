package com.opspulse.domain.entity;

import com.opspulse.domain.enums.AlertEventType;
import com.opspulse.domain.enums.Severity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A raw signal from a monitoring source. High volume and repetitive -- a
 * flapping check re-fires every 30s. Alerts are never shown directly on the
 * dashboard; they roll up into an Incident via dedupKey.
 */
@Entity
@Table(name = "alerts")
@Getter @Setter @NoArgsConstructor
public class Alert {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id")
    private MonitoredService service;

    /** Null only in the brief window before the alert is routed to an incident. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id")
    private Incident incident;

    /** Sender-supplied grouping identity, e.g. "cpu-high:web-03". */
    @Column(nullable = false)
    private String fingerprint;

    /** Derived: hash(serviceId, fingerprint). Denormalised for fast lookup. */
    @Column(name = "dedup_key", nullable = false)
    private String dedupKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    @Column(nullable = false, length = 512)
    private String summary;

    @Column(nullable = false, length = 128)
    private String source = "api";

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 16)
    private AlertEventType eventType = AlertEventType.TRIGGER;

    /** Arbitrary sender labels/annotations. Kept verbatim for debugging. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload = new HashMap<>();

    @Column(name = "received_at", nullable = false, updatable = false, insertable = false)
    private Instant receivedAt;
}
