package com.opspulse.domain.entity;

import com.opspulse.domain.enums.ServiceStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps to table "services". Named MonitoredService because `Service` collides
 * with org.springframework.stereotype.Service in essentially every file that
 * would need to import it.
 */
@Entity
@Table(name = "services")
@Getter @Setter @NoArgsConstructor
public class MonitoredService {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 128)
    private String name;

    /** Stable human-readable identifier used by alert senders, e.g. "checkout-api". */
    @Column(name = "service_key", nullable = false, unique = true, length = 128)
    private String serviceKey;

    @Column(columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id")
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "escalation_policy_id")
    private EscalationPolicy escalationPolicy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ServiceStatus status = ServiceStatus.OPERATIONAL;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;

    @PreUpdate
    void touch() { this.updatedAt = Instant.now(); }

    @Override public boolean equals(Object o) {
        return o instanceof MonitoredService s && id != null && id.equals(s.id);
    }
    @Override public int hashCode() { return getClass().hashCode(); }
}
