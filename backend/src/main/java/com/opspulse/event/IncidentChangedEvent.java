package com.opspulse.event;

import com.opspulse.domain.enums.IncidentEventType;
import com.opspulse.domain.enums.IncidentStatus;

import java.util.UUID;

/**
 * In-process domain event: "incident X changed in way Y".
 *
 * Published INSIDE the transaction that made the change, but every listener
 * uses @TransactionalEventListener(AFTER_COMMIT), so it runs only once the
 * change is durable. A rolled-back transaction therefore never evicts a cache
 * entry, never pushes a phantom update to a browser (phase 6) and never pages
 * anyone (phase 7).
 *
 * Producers (ingest, the incident commands) don't know who listens -- adding
 * SSE fan-out later is a new listener, not an edit to every write path.
 */
public record IncidentChangedEvent(UUID incidentId, IncidentEventType type, IncidentStatus status,
                                   String dedupKey, UUID actorUserId) {}
