package com.garganttua.core.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * How a cache holds its entries: how many, how long, and — for an implementation that stores values outside the
 * process — how they are encoded.
 *
 * <pre>{@code
 * CacheSpec.of()                                  // 10 000 entries, 60 s
 * CacheSpec.ttl(30, TimeUnit.SECONDS).maxEntries(50_000)
 * }</pre>
 *
 * @param maxEntries the most entries held on one node; the least recently used goes first
 * @param ttl        how long an entry lives — the bound on what a lost invalidation can cost
 * @param codec      encodes values for a store outside the process; empty for in-memory implementations
 */
public record CacheSpec(int maxEntries, Duration ttl, Optional<ICacheCodec<?>> codec) {

    /** The default number of entries per node. */
    public static final int DEFAULT_MAX_ENTRIES = 10_000;

    /** The default time to live. */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    public CacheSpec {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("A cache holds at least one entry, got " + maxEntries);
        }
        Objects.requireNonNull(ttl, "Time to live cannot be null");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("A time to live is positive, got " + ttl);
        }
        codec = codec == null ? Optional.empty() : codec;
    }

    /** {@return the default spec: {@value #DEFAULT_MAX_ENTRIES} entries, 60 seconds} */
    public static CacheSpec of() {
        return new CacheSpec(DEFAULT_MAX_ENTRIES, DEFAULT_TTL, Optional.empty());
    }

    /** {@return the default spec with this time to live} */
    public static CacheSpec ttl(long amount, TimeUnit unit) {
        return of().withTtl(Duration.ofNanos(unit.toNanos(amount)));
    }

    /** {@return this spec with that many entries per node} */
    public CacheSpec maxEntries(int entries) {
        return new CacheSpec(entries, this.ttl, this.codec);
    }

    /** {@return this spec with that time to live} */
    public CacheSpec withTtl(Duration duration) {
        return new CacheSpec(this.maxEntries, duration, this.codec);
    }

    /** {@return this spec with that codec, for a store outside the process} */
    public CacheSpec codec(ICacheCodec<?> valueCodec) {
        return new CacheSpec(this.maxEntries, this.ttl, Optional.of(Objects.requireNonNull(valueCodec, "codec")));
    }
}
