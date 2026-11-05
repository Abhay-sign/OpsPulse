package com.opspulse.web;

import com.opspulse.domain.enums.IncidentStatus;
import com.opspulse.domain.enums.Severity;
import com.opspulse.service.IncidentQueryService;
import com.opspulse.web.dto.AlertDtos.AlertResponse;
import com.opspulse.web.dto.IncidentDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentQueryService query;

    /** Every filter optional. openOnly=true is what the dashboard's main view uses. */
    @GetMapping
    public PageResponse<IncidentSummary> list(@RequestParam(required = false) IncidentStatus status,
                                              @RequestParam(required = false) Severity severity,
                                              @RequestParam(required = false) UUID serviceId,
                                              @RequestParam(required = false) UUID assigneeId,
                                              @RequestParam(defaultValue = "false") boolean openOnly,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return query.search(status, severity, serviceId, assigneeId, openOnly, page, size);
    }

    @GetMapping("/{id}")
    public IncidentDetail get(@PathVariable UUID id) { return query.get(id); }

    @GetMapping("/{id}/alerts")
    public PageResponse<AlertResponse> alerts(@PathVariable UUID id,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "50") int size) {
        return query.alerts(id, page, size);
    }

    @GetMapping("/{id}/timeline")
    public List<TimelineEntry> timeline(@PathVariable UUID id) { return query.timeline(id); }
}
