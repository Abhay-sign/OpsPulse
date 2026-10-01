package com.opspulse.domain.enums;

public enum Severity {
    SEV1,  // full outage, customer-facing
    SEV2,  // major degradation
    SEV3,  // minor / partial
    SEV4;  // informational

    /** SEV1 has ordinal 0, so a LOWER ordinal means a HIGHER severity. */
    public boolean isAtLeast(Severity other) {
        return this.ordinal() <= other.ordinal();
    }
}
