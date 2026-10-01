package com.opspulse.domain.entity;

import com.opspulse.domain.enums.IncidentEventType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only. Serves as both the incident timeline in the UI and the audit
 * log for compliance -- one table, written once, never mutated.
 * A null actor means the system acted (ingestion, escalation sweeper).
 */
@Entity
@Table(name = "incident_events")
@Getter @Setter @NoArgsConstructor
public class IncidentEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id")
    private Incident incident;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private UserAccount actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IncidentEventType type;

    @Column(columnDefinition = "text")
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    public static IncidentEvent of(Incident incident, IncidentEventType type,
                                   UserAccount actor, String message) {
        IncidentEvent e = new IncidentEvent();
        e.incident = incident;
        e.type = type;
        e.actor = actor;
        e.message = message;
        return e;
    }
}
