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
     *
     * Ordered for triage, not chronology: nobody-has-it before someone-has-it,
     * then worst severity ('SEV1' sorts before 'SEV2' as a string), then newest.
     * The top row is always the thing the on-call should look at next.
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
            order by case i.status
                       when com.opspulse.domain.enums.IncidentStatus.TRIGGERED then 0
                       when com.opspulse.domain.enums.IncidentStatus.ACKNOWLEDGED then 1
                       else 2 end,
                     i.severity,
                     i.createdAt desc
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

    /** Dashboard: open incidents bucketed by status x severity, in ONE query instead of eight counts. */
    @Query("""
           select i.status, i.severity, count(i) from Incident i
            where i.status <> com.opspulse.domain.enums.IncidentStatus.RESOLVED
            group by i.status, i.severity
           """)
    List<Object[]> countOpenByStatusAndSeverity();

    long countByResolvedAtAfter(Instant after);

    /**
     * Mean time to acknowledge / resolve, in seconds, over incidents in the
     * window. NULL when nothing qualified. The two numbers on-call teams are
     * actually judged by.
     */
    @Query(value = """
           SELECT avg(extract(epoch FROM acknowledged_at - created_at)) FILTER (WHERE acknowledged_at IS NOT NULL) AS mtta,
                  avg(extract(epoch FROM resolved_at - created_at))     FILTER (WHERE resolved_at IS NOT NULL)     AS mttr
             FROM incidents
            WHERE created_at > :since
           """, nativeQuery = true)
    List<Object[]> responseTimesSince(@Param("since") Instant since);

    @Query("""
           select count(i) from Incident i
            where i.status <> com.opspulse.domain.enums.IncidentStatus.RESOLVED
              and i.severity = :severity
           """)
    long countOpenBySeverity(@Param("severity") Severity severity);

    List<Incident> findByServiceIdAndCreatedAtAfter(UUID serviceId, Instant after);
}
