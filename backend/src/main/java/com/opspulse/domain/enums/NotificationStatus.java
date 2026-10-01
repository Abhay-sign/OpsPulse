package com.opspulse.domain.enums;

public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED,  // retryable
    DEAD     // retries exhausted
}
