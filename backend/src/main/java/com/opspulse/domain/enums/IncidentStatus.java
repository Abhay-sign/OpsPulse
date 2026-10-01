package com.opspulse.domain.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * The incident state machine. Transitions live here, in one place, so that
 * no controller or service can invent an illegal move.
 *
 *   TRIGGERED ──► ACKNOWLEDGED ──► RESOLVED
 *       │                              ▲
 *       └──────────────────────────────┘  (resolve without ack is allowed:
 *                                          a RESOLVE alert from the monitoring
 *                                          source auto-closes the incident)
 *
 * RESOLVED is terminal. A recurrence opens a NEW incident -- which is exactly
 * what the partial unique index on dedup_key is there to permit.
 */
public enum IncidentStatus {
    TRIGGERED,
    ACKNOWLEDGED,
    RESOLVED;

    private static final Set<IncidentStatus> OPEN = EnumSet.of(TRIGGERED, ACKNOWLEDGED);

    public boolean isOpen() {
        return OPEN.contains(this);
    }

    public boolean canTransitionTo(IncidentStatus target) {
        return switch (this) {
            case TRIGGERED    -> target == ACKNOWLEDGED || target == RESOLVED;
            case ACKNOWLEDGED -> target == RESOLVED;
            case RESOLVED     -> false;
        };
    }
}
