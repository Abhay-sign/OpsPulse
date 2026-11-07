package com.opspulse.web;

import com.opspulse.domain.enums.IncidentStatus;
import com.opspulse.domain.enums.Severity;
import com.opspulse.security.AuthUser;
import com.opspulse.service.IncidentCommandService;
import com.opspulse.service.IncidentQueryService;
import com.opspulse.web.dto.AlertDtos.AlertResponse;
import com.opspulse.web.dto.IncidentDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Reads: any signed-in user. Commands: RESPONDER or ADMIN (viewers watch only).
 *
 * Optimistic concurrency over HTTP: GET returns ETag: "<version>"; a client may
 * send it back as If-Match on a command. If the incident changed in between,
 * the command is refused with 412 instead of acting on what the user no longer
 * sees. If-Match is optional -- without it, only truly simultaneous writes are
 * caught (409 via @Version).
 */
@RestController
@RequestMapping("/api/v1/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private static final String CAN_ACT = "hasAnyRole('ADMIN','RESPONDER')";

    private final IncidentQueryService query;
    private final IncidentCommandService commands;

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

    /** Declared before /{id} only for readability; Spring prefers the literal path anyway. */
    @GetMapping("/stats")
    public IncidentStats stats() { return query.stats(); }

    @GetMapping("/{id}")
    public ResponseEntity<IncidentDetail> get(@PathVariable UUID id) { return withETag(query.get(id)); }

    @GetMapping("/{id}/alerts")
    public PageResponse<AlertResponse> alerts(@PathVariable UUID id,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "50") int size) {
        return query.alerts(id, page, size);
    }

    @GetMapping("/{id}/timeline")
    public List<TimelineEntry> timeline(@PathVariable UUID id) { return query.timeline(id); }

    // ---- lifecycle commands -------------------------------------------------

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize(CAN_ACT)
    public ResponseEntity<IncidentDetail> acknowledge(@PathVariable UUID id, @AuthenticationPrincipal AuthUser me,
                                                      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return withETag(commands.acknowledge(id, me, version(ifMatch)));
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize(CAN_ACT)
    public ResponseEntity<IncidentDetail> resolve(@PathVariable UUID id, @AuthenticationPrincipal AuthUser me,
                                                  @Valid @RequestBody(required = false) ResolveRequest req,
                                                  @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return withETag(commands.resolve(id, me, req == null ? null : req.note(), version(ifMatch)));
    }

    @PostMapping("/{id}/reassign")
    @PreAuthorize(CAN_ACT)
    public ResponseEntity<IncidentDetail> reassign(@PathVariable UUID id, @AuthenticationPrincipal AuthUser me,
                                                   @Valid @RequestBody ReassignRequest req,
                                                   @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return withETag(commands.reassign(id, me, req.userId(), version(ifMatch)));
    }

    @PatchMapping("/{id}/severity")
    @PreAuthorize(CAN_ACT)
    public ResponseEntity<IncidentDetail> severity(@PathVariable UUID id, @AuthenticationPrincipal AuthUser me,
                                                   @Valid @RequestBody SeverityRequest req,
                                                   @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return withETag(commands.changeSeverity(id, me, req.severity(), version(ifMatch)));
    }

    @PostMapping("/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CAN_ACT)
    public void addNote(@PathVariable UUID id, @AuthenticationPrincipal AuthUser me, @Valid @RequestBody NoteRequest req) {
        commands.addNote(id, me, req.message());
    }

    private static ResponseEntity<IncidentDetail> withETag(IncidentDetail d) {
        return ResponseEntity.ok().eTag(String.valueOf(d.version())).body(d);
    }

    /** Accepts 3, "3" or W/"3". */
    private static Integer version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) return null;
        String digits = ifMatch.replace("W/", "").replace("\"", "").trim();
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("If-Match must be an incident version, e.g. \"3\"");
        }
    }
}
