package com.opspulse.repository;

import com.opspulse.domain.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Outbox drain. SKIP LOCKED lets several sender threads (or replicas) pull
     * disjoint batches without coordinating, and without ever sending the same
     * email twice.
     */
    @Query(value = """
           SELECT n.* FROM notifications n
            WHERE n.status = 'PENDING'
              AND n.next_attempt_at <= now()
            ORDER BY n.next_attempt_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
           """, nativeQuery = true)
    List<Notification> lockPendingBatch(@Param("batchSize") int batchSize);

    List<Notification> findByIncidentId(UUID incidentId);
}
