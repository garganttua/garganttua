package com.garganttua.api.commons.security;

import java.security.SecureRandom;

public final class SecurityRandoms {

    // A single shared, thread-safe SecureRandom (the JDK seeds it lazily on first use). Sharing one
    // instance is the recommended pattern; do NOT defensively copy here — the contract is to hand
    // callers THE shared RNG.
    private static final SecureRandom DEFAULT_SECURE_RANDOM = new SecureRandom();

    private SecurityRandoms() {
    }

    public static SecureRandom secureRandom() {
        return DEFAULT_SECURE_RANDOM;
    }
}
