package com.opspulse.web;

import com.opspulse.service.ApiKeyService;
import com.opspulse.service.ServiceCatalogService;
import com.opspulse.web.dto.ServiceDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/services")
@RequiredArgsConstructor
public class ServiceController {

    private final ServiceCatalogService services;
    private final ApiKeyService apiKeys;

    @GetMapping
    public List<ServiceResponse> list(@RequestParam(required = false) UUID teamId) { return services.list(teamId); }

    @GetMapping("/{id}")
    public ServiceResponse get(@PathVariable UUID id) { return services.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ServiceResponse create(@Valid @RequestBody CreateServiceRequest req) { return services.create(req); }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ServiceResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateServiceRequest req) {
        return services.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID id) { services.delete(id); }

    // ---- API keys (admin only: a key can raise pages for the whole service) ----

    @GetMapping("/{id}/api-keys")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ApiKeyResponse> keys(@PathVariable UUID id) { return apiKeys.list(id); }

    @PostMapping("/{id}/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public CreatedApiKeyResponse createKey(@PathVariable UUID id, @Valid @RequestBody CreateApiKeyRequest req) {
        return apiKeys.create(id, req.name());
    }

    @DeleteMapping("/{id}/api-keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void revokeKey(@PathVariable UUID id, @PathVariable UUID keyId) { apiKeys.revoke(id, keyId); }
}
