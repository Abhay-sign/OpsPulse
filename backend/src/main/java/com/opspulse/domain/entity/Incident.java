package com.opspulse.domain.entity;

import com.opspulse.domain.enums.IncidentStatus;
import com.opspulse.domain.enums.Severity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * The human-facing case. Created by the FIRST alert bearing a given dedup key;
 * every later matching alert attaches to it instead of creating a new one.
 *
 * Concurrency notes:
 *  - dedupKey is protected by a partial unique index (status <> 'RESOLVED').
 *    Two replicas racing to create the same incident: one wins, the other gets
 *    a constraint violation and re-reads the winner.
 *  - @Version guards the lifecycle transitions. Two responders acknowledging
 *    simultaneously: one gets an OptimisticLockException rather than a silent
 *    lost update.
 */
@Entity
@Table(name = "incidents")
@Getter @Setter @NoArgsConstructor
public class Incident {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id")
    private MonitoredService service;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IncidentStatus status = IncidentStatus.TRIGGERED;

    @Column(name = "dedup_key", nullable = false)
    private String dedupKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_user_id")
    private UserAccount assignedUser;

    /** Snapshotted at creation: editing a policy must not rewrite history. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "escalation_policy_id")
    private EscalationPolicy escalationPolicy;

    @Column(name = "current_escalation_level", nullable = false)
    private int currentEscalationLevel = 0;

    @Column(name = "escalation_round", nullable = false)
    private int escalationRound = 0;

    @Column(name = "last_escalated_at")
    private Instant lastEscalatedAt;

    /** How many raw alerts have folded into this incident. Flap indicator. */
    @Column(name = "alert_count", nullable = false)
    private int alertCount = 1;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_user_id")
    private UserAccount resolvedByUser;

    @Version
    @Column(nullable = false)
    private int version;

    @PreUpdate
    void touch() { this.updatedAt = Instant.now(); }

    public boolean isOpen() { return status.isOpen(); }

    @Override public boolean equals(Object o) {
        return o instanceof Incident i && id != null && id.equals(i.id);
    }
    @Override public int hashCode() { return getClass().hashCode(); }
}
