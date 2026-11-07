package com.opspulse.common.error;

/** If-Match named a version that is no longer current: the client acted on a stale view. */
public class PreconditionFailedException extends RuntimeException {
    public PreconditionFailedException(int expected, int actual) {
        super("Incident changed since you loaded it (you had version " + expected + ", current is " + actual
                + "). Reload and try again.");
    }
}
