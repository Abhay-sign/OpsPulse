package com.opspulse.security;

import com.opspulse.domain.enums.Role;

import java.util.UUID;

/**
 * The principal for a human request, rebuilt from JWT claims on every call.
 * No database hit per request -- that is the point of a self-contained token.
 */
public record AuthUser(UUID id, String email, Role role) {
    public boolean isAdmin() { return role == Role.ADMIN; }
}
