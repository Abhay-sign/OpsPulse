package com.opspulse.security;

import java.util.UUID;

/**
 * The principal for a machine request (a monitoring system sending alerts).
 * It identifies a SERVICE, not a person: the key is scoped so a leaked key
 * can only raise alerts for that one service.
 */
public record ApiKeyPrincipal(UUID keyId, UUID serviceId, String serviceKey) {}
