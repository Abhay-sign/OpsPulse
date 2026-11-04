package com.opspulse.common.error;

/** The request is valid but clashes with current state (duplicate key, in-use row, last admin). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) { super(message); }
}
