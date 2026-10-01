package com.opspulse.repository;

import com.opspulse.domain.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    @Query("""
           select k from ApiKey k
             join fetch k.service
            where k.keyHash = :keyHash
              and k.revokedAt is null
           """)
    Optional<ApiKey> findActiveByKeyHash(String keyHash);

    List<ApiKey> findByServiceId(UUID serviceId);

    /**
     * Fire-and-forget last-used stamp. Deliberately a bulk UPDATE rather than
     * a dirty-checked entity write: it must not bump the ingest transaction.
     */
    @Modifying
    @Query("update ApiKey k set k.lastUsedAt = :at where k.id = :id")
    void touchLastUsed(UUID id, Instant at);
}
