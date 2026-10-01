package com.opspulse.repository;

import com.opspulse.domain.entity.Incident;
import com.opspulse.domain.enums.IncidentStatus;
import com.opspulse.domain.enums.Severity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    /**
     * The deduplication lookup. Mirrors the partial unique index
     * uq_incidents_open_dedup exactly -- if this predicate and that index ever
     * drift apart, duplicate incidents start appearing under load.
     */
    @Query("""
           select i from Incident i
            where i.dedupKey = :dedupKey
              and i.status <> com.opspulse.domain.enums.IncidentStatus.RESOLVED
           """)
    Optional<Incident> findOpenByDedupKey(@Param("dedupKey") String dedupKey);

    @Query("""
           select i from Incident i
             join fetch i.service s
             join fetch s.team
             left join fetch i.assignedUser
            where i.id = :id
           """)
    Optional<Incident> findByIdWithDetails(@Param("id") UUID id);

    /**
     * Dashboard list. Every filter is optional: a null parameter disables its
     * clause, which keeps this to one query instead of a Specification tree.
     */
    @Query("""
           select i from Incident i
             join fetch i.service s
             left join fetch i.assignedUser
            where (:status     is null or i.status = :status)
              and (:severity   is null or i.severity = :severity)
              and (:serviceId  is null or s.id = :serviceId)
              and (:assigneeId is null or i.assignedUser.id = :assigneeId)
              and (:openOnly = false or i.status <> com.opspulse.domain.enums.IncidentStatus.RESOLVED)
            order by i.createdAt desc
           """)
    Page<Incident> search(@Param("status") IncidentStatus status,
                          @Param("severity") Severity severity,
                          @Param("serviceId") UUID serviceId,
                          @Param("assigneeId") UUID assigneeId,
                          @Param("openOnly") boolean openOnly,
                          Pageable pageable);

    /**
     * Escalation sweeper input: incidents still un-acknowledged whose current
     * rung has aged past its delay.
     *
     * Native + FOR UPDATE SKIP LOCKED. ShedLock already ensures a single
     * sweeper runs, but SKIP LOCKED means a long-running batch cannot block a
     * responder's acknowledge on the same row -- the sweeper simply skips it.
     */
    @Query(value = """
           SELECT i.* FROM incidents i
            WHERE i.status = 'TRIGGERED'
              AND i.escalation_policy_id IS NOT NULL
              AND i.created_at < now() - make_interval(mins => :minDelayMinutes)
            ORDER BY i.created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
           """, nativeQuery = true)
    List<Incident> lockEscalationCandidates(@Param("minDelayMinutes") int minDelayMinutes,
                                            @Param("batchSize") int batchSize);

    long countByStatus(IncidentStatus status);

    @Query("""
           select count(i) from Incident i
            where i.status <> com.opspulse.domain.enums.IncidentStatus.RESOLVED
              and i.severity = :severity
           """)
    long countOpenBySeverity(@Param("severity") Severity severity);

    List<Incident> findByServiceIdAndCreatedAtAfter(UUID serviceId, Instant after);
}
