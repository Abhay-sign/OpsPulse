package com.opspulse.event;

import com.opspulse.domain.enums.IncidentStatus;
import com.opspulse.ingest.DedupCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drops the Redis dedup pointer once an incident is resolved, so the next
 * alert for that problem goes straight to "create" instead of first trying a
 * stale pointer. Purely an optimisation: the ingest path detects stale
 * pointers on its own.
 */
@Component
@RequiredArgsConstructor
public class DedupCacheInvalidator {

    private final DedupCache dedupCache;

    @TransactionalEventListener
    public void on(IncidentChangedEvent e) {
        if (e.status() == IncidentStatus.RESOLVED && e.dedupKey() != null) {
            dedupCache.evict(e.dedupKey());
        }
    }
}
