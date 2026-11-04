package com.opspulse.web;

import com.opspulse.security.ApiKeyPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * "Test connection" for whoever is wiring up a monitoring tool: proves the
 * X-API-Key is valid and shows which service it raises alerts for.
 * Lives under /alerts so it goes through the API-key filter chain.
 */
@RestController
@RequestMapping("/api/v1/alerts")
public class IngestAuthController {

    @GetMapping("/auth-check")
    public Map<String, Object> check(@AuthenticationPrincipal ApiKeyPrincipal key) {
        return Map.of("serviceId", key.serviceId(), "serviceKey", key.serviceKey(), "keyId", key.keyId());
    }
}
