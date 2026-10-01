package com.opspulse.repository;

import com.opspulse.domain.entity.Alert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface AlertRepository extends JpaRepository<Alert, UUID> {

    Page<Alert> findByIncidentIdOrderByReceivedAtDesc(UUID incidentId, Pageable pageable);

    long countByIncidentId(UUID incidentId);

    /** Flap detection: how noisy has this dedup key been recently. */
    long countByDedupKeyAndReceivedAtAfter(String dedupKey, Instant after);
}
