package com.opspulse.web;

import com.opspulse.ingest.AlertIngestService;
import com.opspulse.security.ApiKeyPrincipal;
import com.opspulse.web.dto.AlertDtos.AlertRequest;
import com.opspulse.web.dto.AlertDtos.IngestResponse;
import com.opspulse.web.dto.AlertDtos.Outcome;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ingest endpoint monitoring systems call. Authenticated by X-API-Key on
 * the ingest filter chain; the key decides which service the alert belongs to,
 * so a sender can never raise alerts for someone else's service.
 *
 * 201 when a new incident was opened, 200 for everything else (attached,
 * resolved, nothing to resolve) -- the body says which.
 */
@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertIngestService ingest;

    @PostMapping
    public ResponseEntity<IngestResponse> ingest(@AuthenticationPrincipal ApiKeyPrincipal sender,
                                                 @Valid @RequestBody AlertRequest req) {
        IngestResponse r = ingest.ingest(sender, req);
        return ResponseEntity.status(r.outcome() == Outcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK).body(r);
    }
}
