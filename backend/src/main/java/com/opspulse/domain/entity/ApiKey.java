package com.opspulse.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Service-scoped ingestion credential. Only the hash is persisted; the
 * plaintext key is returned exactly once, at creation time.
 * keyPrefix is the first few plaintext chars, stored so the UI can show
 * "op_live_a91f..." in a key list without being able to reconstruct the key.
 */
@Entity
@Table(name = "api_keys")
@Getter @Setter @NoArgsConstructor
public class ApiKey {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id")
    private MonitoredService service;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "key_prefix", nullable = false, length = 16)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false, unique = true)
    private String keyHash;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    public boolean isActive() { return revokedAt == null; }
}
