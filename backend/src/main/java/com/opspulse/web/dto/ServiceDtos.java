package com.opspulse.web.dto;

import com.opspulse.domain.entity.ApiKey;
import com.opspulse.domain.entity.MonitoredService;
import com.opspulse.domain.enums.ServiceStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class ServiceDtos {
    private ServiceDtos() {}

    public record ServiceResponse(UUID id, String name, String serviceKey, String description,
                                  UUID teamId, String teamName, UUID escalationPolicyId,
                                  ServiceStatus status, Instant createdAt, Instant updatedAt) {
        public static ServiceResponse from(MonitoredService s) {
            return new ServiceResponse(s.getId(), s.getName(), s.getServiceKey(), s.getDescription(),
                    s.getTeam().getId(), s.getTeam().getName(),
                    s.getEscalationPolicy() == null ? null : s.getEscalationPolicy().getId(),
                    s.getStatus(), s.getCreatedAt(), s.getUpdatedAt());
        }
    }

    public record CreateServiceRequest(@NotBlank @Size(max = 128) String name,
                                       @NotBlank @Size(max = 128) @Pattern(regexp = TeamDtos.SLUG_REGEX) String serviceKey,
                                       String description,
                                       @NotNull UUID teamId) {}

    public record UpdateServiceRequest(@Size(min = 1, max = 128) String name, String description,
                                       UUID teamId, ServiceStatus status) {}

    public record CreateApiKeyRequest(@NotBlank @Size(max = 128) String name) {}

    public record ApiKeyResponse(UUID id, String name, String keyPrefix, boolean active,
                                 Instant lastUsedAt, Instant revokedAt, Instant createdAt) {
        public static ApiKeyResponse from(ApiKey k) {
            return new ApiKeyResponse(k.getId(), k.getName(), k.getKeyPrefix(), k.isActive(),
                    k.getLastUsedAt(), k.getRevokedAt(), k.getCreatedAt());
        }
    }

    /** The ONLY response that ever contains the plaintext key. It is not stored anywhere. */
    public record CreatedApiKeyResponse(ApiKeyResponse key, String plaintextKey) {}
}
