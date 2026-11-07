package com.opspulse.web.dto;

import com.opspulse.domain.entity.Incident;
import com.opspulse.domain.entity.IncidentEvent;
import com.opspulse.domain.entity.UserAccount;
import com.opspulse.domain.enums.IncidentEventType;
import com.opspulse.domain.enums.IncidentStatus;
import com.opspulse.domain.enums.Severity;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public final class IncidentDtos {
    private IncidentDtos() {}

    public record IncidentSummary(UUID id, String title, Severity severity, IncidentStatus status,
                                  UUID serviceId, String serviceName, UUID assignedUserId, String assignedUserName,
                                  int alertCount, Instant createdAt, Instant updatedAt,
                                  Instant acknowledgedAt, Instant resolvedAt) {
        public static IncidentSummary from(Incident i) {
            UserAccount a = i.getAssignedUser();
            return new IncidentSummary(i.getId(), i.getTitle(), i.getSeverity(), i.getStatus(),
                    i.getService().getId(), i.getService().getName(),
                    a == null ? null : a.getId(), a == null ? null : a.getFullName(),
                    i.getAlertCount(), i.getCreatedAt(), i.getUpdatedAt(), i.getAcknowledgedAt(), i.getResolvedAt());
        }
    }

    public record IncidentDetail(UUID id, String title, String description, Severity severity, IncidentStatus status,
                                 String dedupKey, UUID serviceId, String serviceName, UUID teamId, String teamName,
                                 UUID assignedUserId, String assignedUserName, UUID escalationPolicyId,
                                 int currentEscalationLevel, int alertCount, Instant createdAt, Instant updatedAt,
                                 Instant acknowledgedAt, Instant resolvedAt, UUID resolvedByUserId,
                                 String resolvedByName, int version) {
        public static IncidentDetail from(Incident i) {
            UserAccount a = i.getAssignedUser();
            UserAccount r = i.getResolvedByUser();
            var s = i.getService();
            return new IncidentDetail(i.getId(), i.getTitle(), i.getDescription(), i.getSeverity(), i.getStatus(),
                    i.getDedupKey(), s.getId(), s.getName(), s.getTeam().getId(), s.getTeam().getName(),
                    a == null ? null : a.getId(), a == null ? null : a.getFullName(),
                    i.getEscalationPolicy() == null ? null : i.getEscalationPolicy().getId(),
                    i.getCurrentEscalationLevel(), i.getAlertCount(), i.getCreatedAt(), i.getUpdatedAt(),
                    i.getAcknowledgedAt(), i.getResolvedAt(),
                    r == null ? null : r.getId(), r == null ? null : r.getFullName(), i.getVersion());
        }
    }

    /**
     * openBySeverity always has all four keys (zeros included) so the UI never
     * has to guess. Times are seconds, null when there is no data yet.
     */
    public record IncidentStats(long open, long triggered, long acknowledged,
                                Map<Severity, Long> openBySeverity, long resolvedLast24h,
                                Double mttaSeconds7d, Double mttrSeconds7d) {}

    // ---- commands -----------------------------------------------------------

    public record ResolveRequest(@jakarta.validation.constraints.Size(max = 4000) String note) {}

    public record ReassignRequest(@jakarta.validation.constraints.NotNull UUID userId) {}

    public record NoteRequest(@jakarta.validation.constraints.NotBlank
                              @jakarta.validation.constraints.Size(max = 4000) String message) {}

    public record SeverityRequest(@jakarta.validation.constraints.NotNull Severity severity) {}

    /** actorId/actorName are null when the system acted (ingest, escalation). */
    public record TimelineEntry(UUID id, IncidentEventType type, String message, UUID actorId, String actorName,
                                Map<String, Object> payload, Instant createdAt) {
        public static TimelineEntry from(IncidentEvent e) {
            UserAccount a = e.getActor();
            return new TimelineEntry(e.getId(), e.getType(), e.getMessage(),
                    a == null ? null : a.getId(), a == null ? "system" : a.getFullName(),
                    e.getPayload(), e.getCreatedAt());
        }
    }

    /**
     * Our own page envelope. Serialising Spring's PageImpl directly exposes
     * internals (pageable, sort objects) whose JSON shape isn't stable across
     * Spring versions -- Spring Data itself warns against it.
     */
    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <E, T> PageResponse<T> of(Page<E> p, Function<E, T> mapper) {
            return new PageResponse<>(p.getContent().stream().map(mapper).toList(),
                    p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
        }
    }
}
