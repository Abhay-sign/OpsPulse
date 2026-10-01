package com.opspulse.domain.enums;

public enum Role {
    ADMIN,      // manage users, teams, services, policies
    RESPONDER,  // acknowledge / resolve / reassign incidents
    VIEWER      // read-only dashboard access
}
