package com.opspulse.ingest;

import com.opspulse.security.ApiKeyHasher;

import java.util.UUID;

/**
 * dedup_key = sha256(serviceId + ":" + fingerprint)
 *
 * The service id is part of the key so two services that happen to send the
 * same fingerprint ("disk-full") never merge into one incident. Hashing gives
 * a fixed-length value no matter how long the sender's fingerprint is.
 */
public final class DedupKeys {
    private DedupKeys() {}

    public static String of(UUID serviceId, String fingerprint) {
        return ApiKeyHasher.hash(serviceId + ":" + fingerprint);
    }
}
