package com.garganttua.core.cache.fixtures;

import java.util.Optional;
import java.util.function.Supplier;

import com.garganttua.core.cache.CacheStats;
import com.garganttua.core.cache.ICache;

/** A cache implementation known only through its scanned factory. */
public class CountingCache<K, V> implements ICache<K, V> {

    private final ICache<K, V> delegate;

    public CountingCache(ICache<K, V> delegate) {
        this.delegate = delegate;
    }

    @Override public String name() { return this.delegate.name(); }
    @Override public Optional<V> get(K key) { return this.delegate.get(key); }
    @Override public V get(K key, Supplier<? extends V> loader) { return this.delegate.get(key, loader); }
    @Override public void put(K key, V value) { this.delegate.put(key, value); }
    @Override public void invalidate(K key) { this.delegate.invalidate(key); }
    @Override public void invalidateAll() { this.delegate.invalidateAll(); }
    @Override public CacheStats stats() { return this.delegate.stats(); }
}
