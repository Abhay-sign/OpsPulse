package com.opspulse.service;

import com.opspulse.common.error.NotFoundException;
import com.opspulse.domain.enums.IncidentStatus;
import com.opspulse.domain.enums.Severity;
import com.opspulse.repository.AlertRepository;
import com.opspulse.repository.IncidentEventRepository;
import com.opspulse.repository.IncidentRepository;
import com.opspulse.web.dto.AlertDtos.AlertResponse;
import com.opspulse.web.dto.IncidentDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read side of incidents. Writes (ack, resolve, reassign, notes) arrive in phase 4. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IncidentQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final IncidentRepository incidents;
    private final AlertRepository alerts;
    private final IncidentEventRepository events;

    public PageResponse<IncidentSummary> search(IncidentStatus status, Severity severity, UUID serviceId,
                                                UUID assigneeId, boolean openOnly, int page, int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return PageResponse.of(incidents.search(status, severity, serviceId, assigneeId, openOnly, pageable),
                IncidentSummary::from);
    }

    public IncidentStats stats() {
        Map<Severity, Long> bySeverity = new EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) bySeverity.put(s, 0L);
        long triggered = 0, acknowledged = 0;
        for (Object[] row : incidents.countOpenByStatusAndSeverity()) {
            IncidentStatus status = (IncidentStatus) row[0];
            long n = ((Number) row[2]).longValue();
            bySeverity.merge((Severity) row[1], n, Long::sum);
            if (status == IncidentStatus.TRIGGERED) triggered += n; else acknowledged += n;
        }
        Instant now = Instant.now();
        Object[] times = incidents.responseTimesSince(now.minus(Duration.ofDays(7))).get(0);
        return new IncidentStats(triggered + acknowledged, triggered, acknowledged, bySeverity,
                incidents.countByResolvedAtAfter(now.minus(Duration.ofHours(24))),
                times[0] == null ? null : ((Number) times[0]).doubleValue(),
                times[1] == null ? null : ((Number) times[1]).doubleValue());
    }

    public IncidentDetail get(UUID id) {
        return IncidentDetail.from(incidents.findByIdWithDetails(id)
                .orElseThrow(() -> new NotFoundException("Incident", id)));
    }

    public PageResponse<AlertResponse> alerts(UUID incidentId, int page, int size) {
        requireExists(incidentId);
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return PageResponse.of(alerts.findByIncidentIdOrderByReceivedAtDesc(incidentId, pageable), AlertResponse::from);
    }

    public List<TimelineEntry> timeline(UUID incidentId) {
        requireExists(incidentId);
        return events.findTimeline(incidentId).stream().map(TimelineEntry::from).toList();
    }

    private void requireExists(UUID id) {
        if (!incidents.existsById(id)) throw new NotFoundException("Incident", id);
    }
}
