package com.opspulse.service;

import com.opspulse.common.error.NotFoundException;
import com.opspulse.domain.entity.ApiKey;
import com.opspulse.repository.ApiKeyRepository;
import com.opspulse.repository.MonitoredServiceRepository;
import com.opspulse.security.ApiKeyHasher;
import com.opspulse.security.ApiKeyPrincipal;
import com.opspulse.web.dto.ServiceDtos.ApiKeyResponse;
import com.opspulse.web.dto.ServiceDtos.CreatedApiKeyResponse;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

    /** Don't write last_used_at on every alert -- once a minute is plenty for a "last seen" column. */
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final ApiKeyRepository keys;
    private final MonitoredServiceRepository services;
    private final EntityManager em;

    @Transactional
    public CreatedApiKeyResponse create(UUID serviceId, String name) {
        var service = services.findById(serviceId).orElseThrow(() -> new NotFoundException("Service", serviceId));
        String plaintext = ApiKeyHasher.generate();
        ApiKey k = new ApiKey();
        k.setService(service);
        k.setName(name.trim());
        k.setKeyPrefix(ApiKeyHasher.displayPrefix(plaintext));
        k.setKeyHash(ApiKeyHasher.hash(plaintext));
        keys.saveAndFlush(k);
        em.refresh(k);
        return new CreatedApiKeyResponse(ApiKeyResponse.from(k), plaintext);
    }

    @Transactional(readOnly = true)
    public List<ApiKeyResponse> list(UUID serviceId) {
        return keys.findByServiceId(serviceId).stream()
                .sorted(Comparator.comparing(ApiKey::getCreatedAt).reversed())
                .map(ApiKeyResponse::from).toList();
    }

    /** Revoke, not delete: keeps "which key sent this?" answerable later. Idempotent. */
    @Transactional
    public void revoke(UUID serviceId, UUID keyId) {
        ApiKey k = keys.findById(keyId)
                .filter(x -> x.getService().getId().equals(serviceId))
                .orElseThrow(() -> new NotFoundException("API key", keyId));
        if (k.getRevokedAt() == null) k.setRevokedAt(Instant.now());
    }

    /** Called by ApiKeyAuthFilter on every ingest request: one indexed lookup by hash. */
    @Transactional
    public Optional<ApiKeyPrincipal> authenticate(String plaintext) {
        return keys.findActiveByKeyHash(ApiKeyHasher.hash(plaintext)).map(k -> {
            Instant now = Instant.now();
            if (k.getLastUsedAt() == null || k.getLastUsedAt().isBefore(now.minus(TOUCH_INTERVAL))) {
                keys.touchLastUsed(k.getId(), now);
            }
            return new ApiKeyPrincipal(k.getId(), k.getService().getId(), k.getService().getServiceKey());
        });
    }
}
