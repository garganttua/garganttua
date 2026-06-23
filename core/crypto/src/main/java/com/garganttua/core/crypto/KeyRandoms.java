package com.garganttua.core.crypto;

import com.garganttua.core.observability.Logger;
import java.security.SecureRandom;

/**
 * Holder for the framework-wide {@link SecureRandom} instance, created at class
 * load. All key generation and IV creation in this package draws from
 * {@link #secureRandom()}.
 *
 * <p>The instance is self-seeding: a {@link SecureRandom} seeds itself lazily on
 * its first {@code nextBytes}/{@code nextInt} call, so no explicit pre-seeding is
 * performed here.
 */
public final class KeyRandoms {
    private static final Logger log = Logger.getLogger(KeyRandoms.class);

	private static final SecureRandom DEFAULT_SECURE_RANDOM = new SecureRandom();

	private KeyRandoms() {
	}

	/**
	 * {@return the shared {@link SecureRandom} instance}
	 *
	 * <p>Package-private by design: the single shared instance is an internal
	 * cryptographic primitive consumed only by this package's key/IV generation,
	 * and must not be handed out across module boundaries.
	 */
	static SecureRandom secureRandom() {
		log.trace("Retrieving SecureRandom instance");
		return DEFAULT_SECURE_RANDOM;
	}
}
