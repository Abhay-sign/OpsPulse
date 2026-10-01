package com.opspulse.domain.enums;

/**
 * Doubles as the incident timeline and the audit log.
 * Keep in sync with the CHECK constraint in V1__init.sql.
 */
public enum IncidentEventType {
    CREATED,
    ALERT_ATTACHED,
    ACKNOWLEDGED,
    ESCALATED,
    REASSIGNED,
    NOTE_ADDED,
    SEVERITY_CHANGED,
    RESOLVED,
    NOTIFICATION_SENT
}
