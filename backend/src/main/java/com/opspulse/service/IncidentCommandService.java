package com.opspulse.service;

import com.opspulse.common.error.ConflictException;
import com.opspulse.common.error.NotFoundException;
import com.opspulse.common.error.PreconditionFailedException;
import com.opspulse.domain.entity.Incident;
import com.opspulse.domain.entity.IncidentEvent;
import com.opspulse.domain.entity.UserAccount;
import com.opspulse.domain.enums.IncidentEventType;
import com.opspulse.domain.enums.IncidentStatus;
import com.opspulse.domain.enums.Role;
import com.opspulse.domain.enums.Severity;
import com.opspulse.event.IncidentChangedEvent;
import com.opspulse.repository.IncidentRepository;
import com.opspulse.repository.UserAccountRepository;
import com.opspulse.security.AuthUser;
import com.opspulse.web.dto.IncidentDtos.IncidentDetail;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Everything a human does to an incident. Each command, in ONE transaction:
 *   1. loads the incident and checks the optional If-Match version,
 *   2. asks IncidentStatus whether the transition is legal,
 *   3. mutates the entity,
 *   4. appends a row to incident_events (timeline + audit log),
 *   5. flushes, so a concurrent edit surfaces HERE as an optimistic-lock
 *      failure (-> 409) and the response carries the new version,
 *   6. publishes IncidentChangedEvent for after-commit listeners.
 *
 * Two kinds of "someone else got there first", handled differently:
 *   - Stale VIEW: you loaded version 3 a minute ago, someone resolved it since.
 *     Caught by If-Match before we touch anything -> 412.
 *   - Truly CONCURRENT write: two transactions read version 3 at the same
 *     instant. Caught by @Version at flush -> 409. One wins, one is told.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class IncidentCommandService {

    private final IncidentRepository incidents;
    private final UserAccountRepository users;
    private final EntityManager em;
    private final ApplicationEventPublisher events;

    /**
     * Acknowledging means "I've got this": it stops escalation (phase 7) and,
     * if nobody owns the incident yet, makes the acknowledger the owner.
     */
    public IncidentDetail acknowledge(UUID id, AuthUser me, Integer ifMatch) {
        Incident i = load(id, ifMatch);
        transition(i, IncidentStatus.ACKNOWLEDGED, "acknowledge");
        UserAccount actor = actor(me);
        i.setStatus(IncidentStatus.ACKNOWLEDGED);
        i.setAcknowledgedAt(Instant.now());
        if (i.getAssignedUser() == null) i.setAssignedUser(actor);
        return commit(i, actor, IncidentEventType.ACKNOWLEDGED, "Acknowledged by " + actor.getFullName(), Map.of());
    }

    public IncidentDetail resolve(UUID id, AuthUser me, String note, Integer ifMatch) {
        Incident i = load(id, ifMatch);
        transition(i, IncidentStatus.RESOLVED, "resolve");
        UserAccount actor = actor(me);
        i.setStatus(IncidentStatus.RESOLVED);
        i.setResolvedAt(Instant.now());
        i.setResolvedByUser(actor);
        String msg = "Resolved by " + actor.getFullName() + (StringUtils.hasText(note) ? ": " + note.trim() : "");
        return commit(i, actor, IncidentEventType.RESOLVED, msg, Map.of());
    }

    public IncidentDetail reassign(UUID id, AuthUser me, UUID targetUserId, Integer ifMatch) {
        Incident i = load(id, ifMatch);
        if (!i.isOpen()) throw new ConflictException("Cannot reassign a " + i.getStatus() + " incident");
        UserAccount target = users.findById(targetUserId)
                .orElseThrow(() -> new NotFoundException("User", targetUserId));
        if (!target.isActive()) throw new IllegalArgumentException("Cannot assign to a deactivated user");
        if (target.getRole() == Role.VIEWER) throw new IllegalArgumentException("Viewers cannot own incidents");

        UserAccount previous = i.getAssignedUser();
        if (previous != null && previous.getId().equals(target.getId())) {
            return IncidentDetail.from(i); // already theirs: no-op, no timeline noise
        }
        UserAccount actor = actor(me);
        i.setAssignedUser(target);
        Map<String, Object> payload = new HashMap<>();
        payload.put("fromUserId", previous == null ? null : previous.getId().toString());
        payload.put("toUserId", target.getId().toString());
        String msg = actor.getFullName() + " assigned the incident to " + target.getFullName()
                + (previous == null ? "" : " (was " + previous.getFullName() + ")");
        return commit(i, actor, IncidentEventType.REASSIGNED, msg, payload);
    }

    public IncidentDetail changeSeverity(UUID id, AuthUser me, Severity severity, Integer ifMatch) {
        Incident i = load(id, ifMatch);
        if (!i.isOpen()) throw new ConflictException("Cannot change severity of a " + i.getStatus() + " incident");
        Severity before = i.getSeverity();
        if (before == severity) return IncidentDetail.from(i);
        UserAccount actor = actor(me);
        i.setSeverity(severity);
        return commit(i, actor, IncidentEventType.SEVERITY_CHANGED,
                actor.getFullName() + " changed severity from " + before + " to " + severity,
                Map.of("from", before.name(), "to", severity.name()));
    }

    /**
     * Notes don't change the incident row, so they don't bump @Version and
     * never conflict with anyone. Allowed after resolution too -- that's where
     * root-cause and follow-up notes go.
     */
    public void addNote(UUID id, AuthUser me, String message) {
        Incident i = incidents.findById(id).orElseThrow(() -> new NotFoundException("Incident", id));
        UserAccount actor = actor(me);
        em.persist(IncidentEvent.of(i, IncidentEventType.NOTE_ADDED, actor, message.trim()));
        events.publishEvent(new IncidentChangedEvent(i.getId(), IncidentEventType.NOTE_ADDED, i.getStatus(),
                i.getDedupKey(), actor.getId()));
    }

    // ------------------------------------------------------------------ helpers

    private Incident load(UUID id, Integer ifMatch) {
        Incident i = incidents.findByIdWithDetails(id).orElseThrow(() -> new NotFoundException("Incident", id));
        if (ifMatch != null && ifMatch != i.getVersion()) {
            throw new PreconditionFailedException(ifMatch, i.getVersion());
        }
        return i;
    }

    /** The state machine lives in IncidentStatus; this only turns "no" into a readable 409. */
    private static void transition(Incident i, IncidentStatus target, String verb) {
        if (!i.getStatus().canTransitionTo(target)) {
            throw new ConflictException("Cannot " + verb + " an incident that is already " + i.getStatus());
        }
    }

    private UserAccount actor(AuthUser me) {
        return users.findById(me.id()).orElseThrow(() -> new NotFoundException("User", me.id()));
    }

    private IncidentDetail commit(Incident i, UserAccount actor, IncidentEventType type, String message,
                                  Map<String, Object> payload) {
        IncidentEvent e = IncidentEvent.of(i, type, actor, message);
        e.setPayload(new HashMap<>(payload));
        em.persist(e);
        em.flush(); // version check happens now, and the DTO gets the new version
        events.publishEvent(new IncidentChangedEvent(i.getId(), type, i.getStatus(), i.getDedupKey(), actor.getId()));
        return IncidentDetail.from(i);
    }
}
