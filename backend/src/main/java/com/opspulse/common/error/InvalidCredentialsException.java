package com.opspulse.common.error;

/**
 * Deliberately one message for every login/refresh failure. Saying "no such
 * email" vs "wrong password" lets an attacker enumerate valid accounts.
 */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() { super("Invalid credentials"); }
}
