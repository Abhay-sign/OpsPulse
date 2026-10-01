package com.opspulse.domain.enums;

public enum ServiceStatus {
    OPERATIONAL,
    DEGRADED,
    DOWN,
    MAINTENANCE   // suppresses escalation; alerts still ingest
}
