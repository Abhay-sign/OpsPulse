package com.opspulse.domain.enums;

/** Prometheus-style: a source either fires an alert or clears it. */
public enum AlertEventType {
    TRIGGER,
    RESOLVE
}
