package com.opspulse.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * API keys are hashed with SHA-256, NOT bcrypt -- and that is deliberate.
 *
 * bcrypt exists to make guessing LOW-entropy secrets (human passwords) slow.
 * An API key is 32 random bytes (256 bits); nobody can brute-force that
 * regardless of hash speed. Meanwhile the ingest path verifies a key on EVERY
 * alert, and bcrypt at cost 12 is ~250ms -- it would cap ingestion at a few
 * requests per second per core. bcrypt is also salted, so the hash couldn't be
 * looked up by a unique index; you'd have to scan and compare every key.
 *
 * A fast, unsalted, deterministic hash gives an O(1) indexed lookup and is
 * exactly as safe for a high-entropy secret.
 */
public final class ApiKeyHasher {

    public static final String KEY_PREFIX = "op_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyHasher() {}

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String plaintext) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JVM ships SHA-256
        }
    }

    /** First 10 chars, e.g. "op_a91fK2x" -- enough to tell keys apart in a list. */
    public static String displayPrefix(String plaintext) {
        return plaintext.substring(0, Math.min(10, plaintext.length()));
    }
}
