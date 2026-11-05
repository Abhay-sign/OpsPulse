package com.opspulse.ingest;

import com.opspulse.common.error.RateLimitedException;
import com.opspulse.config.OpsPulseProperties;
import com.opspulse.domain.entity.Alert;
import com.opspulse.domain.entity.Incident;
import com.opspulse.domain.entity.IncidentEvent;
import com.opspulse.domain.entity.MonitoredService;
import com.opspulse.domain.enums.AlertEventType;
import com.opspulse.domain.enums.IncidentEventType;
import com.opspulse.domain.enums.IncidentStatus;
import com.opspulse.domain.enums.Severity;
import com.opspulse.repository.IncidentRepository;
import com.opspulse.security.ApiKeyPrincipal;
import com.opspulse.web.dto.AlertDtos.AlertRequest;
import com.opspulse.web.dto.AlertDtos.IngestResponse;
import com.opspulse.web.dto.AlertDtos.Outcome;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The hot path: one alert in, at most one open incident per dedup key out.
 *
 * TRIGGER
 *   1. Redis pointer warm?  -> atomic conditional UPDATE (attach).  0 rows => pointer stale, fall through.
 *   2. SELECT open incident -> found: attach.
 *   3. INSERT ... ON CONFLICT (dedup_key) WHERE status <> 'RESOLVED' DO NOTHING RETURNING id
 *        row returned  -> we created it.
 *        nothing back  -> a concurrent request created it a moment ago; loop to step 2.
 *
 * RESOLVE
 *   Close the open incident for this key (if any), via the entity so @Version
 *   protects against racing with a human acknowledging it.
 *
 * Why ON CONFLICT instead of "insert, catch the unique violation, retry"?
 * In Postgres a failed statement ABORTS the whole transaction -- after
 * catching the exception you can't run another query in it, so you'd need a
 * second transaction (or a savepoint). ON CONFLICT DO NOTHING never raises:
 * if another transaction is mid-way through inserting the same key, Postgres
 * makes us WAIT for it to commit, then reports "no row inserted". The very
 * next SELECT (READ COMMITTED takes a fresh snapshot per statement) sees the
 * winner. One transaction, no exception, no retry storm.
 *
 * Transactions are opened with TransactionTemplate rather than @Transactional
 * so the Redis work (rate limit before, pointer update after) sits OUTSIDE the
 * DB transaction, and so the retry loop for RESOLVE can open a fresh
 * transaction per attempt -- @Transactional on a method called from inside the
 * same class would be bypassed (Spring's proxy only sees external calls).
 */
@Slf4j
@Service
public class AlertIngestService {

    private static final int MAX_DEDUP_ROUNDS = 5;
    private static final int MAX_RESOLVE_ATTEMPTS = 3;

    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final IncidentRepository incidents;
    private final DedupCache dedupCache;
    private final RateLimiter rateLimiter;
    private final OpsPulseProperties props;
    private final TransactionTemplate tx;

    public AlertIngestService(JdbcTemplate jdbc, EntityManager em, IncidentRepository incidents,
                              DedupCache dedupCache, RateLimiter rateLimiter, OpsPulseProperties props,
                              PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.em = em;
        this.incidents = incidents;
        this.dedupCache = dedupCache;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    public IngestResponse ingest(ApiKeyPrincipal sender, AlertRequest req) {
        long wait = rateLimiter.tryAcquire(sender.keyId(), props.getIngest().getRateLimitPerMinute());
        if (wait > 0) throw new RateLimitedException(wait);

        String dedupKey = DedupKeys.of(sender.serviceId(), req.fingerprint());
        if (req.eventTypeOrDefault() == AlertEventType.RESOLVE) {
            IngestResponse r = resolveWithRetry(sender, req, dedupKey);
            if (r.outcome() == Outcome.RESOLVED) dedupCache.evict(dedupKey);
            return r;
        }

        validateTrigger(req);
        IngestResponse r = tx.execute(status -> trigger(sender, req, dedupKey));
        // After commit: never point the cache at a row that might still roll back.
        dedupCache.put(dedupKey, r.incidentId());
        return r;
    }

    // ------------------------------------------------------------------ TRIGGER

    private IngestResponse trigger(ApiKeyPrincipal sender, AlertRequest req, String dedupKey) {
        Severity severity = req.severity();

        Optional<UUID> pointer = dedupCache.get(dedupKey);
        if (pointer.isPresent()) {
            Optional<IngestResponse> attached = tryAttach(pointer.get(), sender, req, dedupKey, severity);
            if (attached.isPresent()) return attached.get();
            dedupCache.evict(dedupKey); // stale: that incident was resolved
        }

        for (int round = 0; round < MAX_DEDUP_ROUNDS; round++) {
            Optional<UUID> open = findOpen(dedupKey);
            if (open.isPresent()) {
                Optional<IngestResponse> attached = tryAttach(open.get(), sender, req, dedupKey, severity);
                if (attached.isPresent()) return attached.get();
                continue; // resolved between our SELECT and UPDATE -- look again
            }
            Optional<UUID> created = insertIfAbsent(sender.serviceId(), req, dedupKey, severity);
            if (created.isPresent()) {
                UUID incidentId = created.get();
                Alert alert = saveAlert(sender.serviceId(), incidentId, req, dedupKey, severity);
                recordEvent(incidentId, IncidentEventType.CREATED,
                        "Incident opened by " + sourceOf(req) + " alert", Map.of("alertId", alert.getId()));
                return new IngestResponse(alert.getId(), incidentId, Outcome.CREATED, dedupKey);
            }
            // lost the race: a concurrent request inserted it; next round's SELECT will find it
            log.debug("Lost insert race for {}, re-reading", dedupKey);
        }
        // Only reachable if the same key is being opened AND resolved in a tight loop.
        throw new IllegalStateException("Could not route alert after " + MAX_DEDUP_ROUNDS + " attempts");
    }

    /**
     * Attach in ONE statement: lock the row, bump the counter, raise severity
     * if the new alert is worse, and return the severity it had before.
     *
     * A bulk UPDATE, not entity.setAlertCount(n + 1): read-modify-write in Java
     * loses increments under concurrency, and dirty-checking would bump
     * @Version, making a responder's acknowledge fail just because another
     * alert arrived. alert_count is a counter, not something humans edit.
     *
     * LEAST() works because 'SEV1' < 'SEV2' < 'SEV3' < 'SEV4' as strings, and
     * SEV1 is the most severe.
     */
    private Optional<IngestResponse> tryAttach(UUID incidentId, ApiKeyPrincipal sender, AlertRequest req,
                                               String dedupKey, Severity severity) {
        List<String> previous = jdbc.queryForList("""
                WITH cur AS (
                    SELECT id, severity FROM incidents
                     WHERE id = ? AND dedup_key = ? AND status <> 'RESOLVED'
                     FOR UPDATE
                )
                UPDATE incidents i
                   SET alert_count = i.alert_count + 1,
                       severity    = LEAST(i.severity, ?),
                       updated_at  = now()
                  FROM cur
                 WHERE i.id = cur.id
             RETURNING cur.severity
                """, String.class, incidentId, dedupKey, severity.name());
        if (previous.isEmpty()) return Optional.empty();

        Alert alert = saveAlert(sender.serviceId(), incidentId, req, dedupKey, severity);
        Severity before = Severity.valueOf(previous.get(0));
        if (severity.ordinal() < before.ordinal()) {
            recordEvent(incidentId, IncidentEventType.SEVERITY_CHANGED,
                    "Severity raised from " + before + " to " + severity + " by a new alert",
                    Map.of("from", before.name(), "to", severity.name(), "alertId", alert.getId()));
        }
        return Optional.of(new IngestResponse(alert.getId(), incidentId, Outcome.ATTACHED, dedupKey));
    }

    private Optional<UUID> findOpen(String dedupKey) {
        return jdbc.queryForList(
                "SELECT id FROM incidents WHERE dedup_key = ? AND status <> 'RESOLVED'", UUID.class, dedupKey)
                .stream().findFirst();
    }

    /**
     * The conflict target names the PARTIAL index's columns and predicate, so
     * Postgres arbitrates against uq_incidents_open_dedup. The service's
     * current escalation policy is copied into the incident in the same
     * statement (a snapshot: editing the policy later doesn't rewrite history).
     */
    private Optional<UUID> insertIfAbsent(UUID serviceId, AlertRequest req, String dedupKey, Severity severity) {
        return jdbc.queryForList("""
                INSERT INTO incidents (service_id, title, description, severity, status, dedup_key,
                                       escalation_policy_id, alert_count)
                SELECT s.id, ?, ?, ?, 'TRIGGERED', ?, s.escalation_policy_id, 1
                  FROM services s
                 WHERE s.id = ?
                ON CONFLICT (dedup_key) WHERE status <> 'RESOLVED' DO NOTHING
                RETURNING id
                """, UUID.class, req.summary().trim(), req.description(), severity.name(), dedupKey, serviceId)
                .stream().findFirst();
    }

    // ------------------------------------------------------------------ RESOLVE

    private IngestResponse resolveWithRetry(ApiKeyPrincipal sender, AlertRequest req, String dedupKey) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> resolve(sender, req, dedupKey));
            } catch (ObjectOptimisticLockingFailureException e) {
                // A human acknowledged or edited it at the same instant. Re-read and try again.
                if (attempt >= MAX_RESOLVE_ATTEMPTS) throw e;
                log.debug("Optimistic lock conflict resolving {}, retrying", dedupKey);
            }
        }
    }

    private IngestResponse resolve(ApiKeyPrincipal sender, AlertRequest req, String dedupKey) {
        Optional<Incident> open = incidents.findOpenByDedupKey(dedupKey);
        Severity severity = req.severity() != null ? req.severity()
                : open.map(Incident::getSeverity).orElse(Severity.SEV4);

        if (open.isEmpty()) {
            // Nothing to close (already resolved by a human, or never opened). Keep the alert for the record.
            Alert alert = saveAlert(sender.serviceId(), null, req, dedupKey, severity);
            return new IngestResponse(alert.getId(), null, Outcome.NO_OPEN_INCIDENT, dedupKey);
        }

        Incident incident = open.get();
        if (!incident.getStatus().canTransitionTo(IncidentStatus.RESOLVED)) {
            throw new IllegalStateException("Open incident cannot be resolved from " + incident.getStatus());
        }
        incident.setStatus(IncidentStatus.RESOLVED);
        incident.setResolvedAt(Instant.now());
        Alert alert = saveAlert(sender.serviceId(), incident.getId(), req, dedupKey, severity);
        recordEvent(incident.getId(), IncidentEventType.RESOLVED,
                "Auto-resolved: " + sourceOf(req) + " reported the problem cleared", Map.of("alertId", alert.getId()));
        return new IngestResponse(alert.getId(), incident.getId(), Outcome.RESOLVED, dedupKey);
    }

    // ------------------------------------------------------------------ helpers

    private Alert saveAlert(UUID serviceId, UUID incidentId, AlertRequest req, String dedupKey, Severity severity) {
        Alert a = new Alert();
        a.setService(em.getReference(MonitoredService.class, serviceId)); // no SELECT, just the FK
        if (incidentId != null) a.setIncident(em.getReference(Incident.class, incidentId));
        a.setFingerprint(req.fingerprint());
        a.setDedupKey(dedupKey);
        a.setSeverity(severity);
        a.setSummary(StringUtils.hasText(req.summary()) ? req.summary().trim() : "(resolve)");
        a.setSource(sourceOf(req));
        a.setEventType(req.eventTypeOrDefault());
        a.setPayload(req.payload() == null ? new HashMap<>() : new HashMap<>(req.payload()));
        em.persist(a);
        return a;
    }

    private void recordEvent(UUID incidentId, IncidentEventType type, String message, Map<String, Object> payload) {
        IncidentEvent e = IncidentEvent.of(em.getReference(Incident.class, incidentId), type, null, message);
        e.setPayload(new HashMap<>(payload));
        em.persist(e);
    }

    private static String sourceOf(AlertRequest req) {
        return StringUtils.hasText(req.source()) ? req.source().trim() : "api";
    }

    private static void validateTrigger(AlertRequest req) {
        if (req.severity() == null) throw new IllegalArgumentException("severity is required for TRIGGER alerts");
        if (!StringUtils.hasText(req.summary())) throw new IllegalArgumentException("summary is required for TRIGGER alerts");
    }
}
