package com.opspulse.web.dto;

import com.opspulse.domain.entity.Alert;
import com.opspulse.domain.enums.AlertEventType;
import com.opspulse.domain.enums.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class AlertDtos {
    private AlertDtos() {}

    /**
     * What a monitoring system sends. Only fingerprint is always required;
     * severity and summary are required for TRIGGER (checked in the service,
     * since a RESOLVE legitimately omits them).
     */
    public record AlertRequest(
            @NotBlank @Size(max = 255) String fingerprint,
            AlertEventType eventType,
            Severity severity,
            @Size(max = 512) String summary,
            String description,
            @Size(max = 128) String source,
            Map<String, Object> payload) {

        public AlertEventType eventTypeOrDefault() {
            return eventType == null ? AlertEventType.TRIGGER : eventType;
        }
    }

    /** What happened to the alert, so a sender (or a curl user) can see dedup working. */
    public enum Outcome { CREATED, ATTACHED, RESOLVED, NO_OPEN_INCIDENT }

    public record IngestResponse(UUID alertId, UUID incidentId, Outcome outcome, String dedupKey) {}

    public record AlertResponse(UUID id, String fingerprint, Severity severity, String summary, String source,
                                AlertEventType eventType, Map<String, Object> payload, Instant receivedAt) {
        public static AlertResponse from(Alert a) {
            return new AlertResponse(a.getId(), a.getFingerprint(), a.getSeverity(), a.getSummary(), a.getSource(),
                    a.getEventType(), a.getPayload(), a.getReceivedAt());
        }
    }
}
