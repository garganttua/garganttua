package com.garganttua.core.cache;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.garganttua.core.reflection.annotations.Reflected;

/** A cache that keeps nothing: every read goes to the source. Turns a declared cache off without changing code. */
@Reflected
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class NoCache<K, V> implements ICache<K, V> {

    private final String name;
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong invalidations = new AtomicLong();

    public NoCache(String name) {
        this.name = Objects.requireNonNull(name, "Cache name cannot be null");
    }

    @Override
    public String name() {
        return this.name;
    }

    @Override
    public Optional<V> get(K key) {
        this.misses.incrementAndGet();
        return Optional.empty();
    }

    @Override
    public V get(K key, Supplier<? extends V> loader) {
        this.misses.incrementAndGet();
        return loader.get();
    }

    @Override
    public void put(K key, V value) {
        // keeps nothing
    }

    @Override
    public void invalidate(K key) {
        this.invalidations.incrementAndGet();
    }

    @Override
    public void invalidateAll() {
        this.invalidations.incrementAndGet();
    }

    @Override
    public CacheStats stats() {
        return new CacheStats(0, this.misses.get(), this.invalidations.get(), 0);
    }
}
