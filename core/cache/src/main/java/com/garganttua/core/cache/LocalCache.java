package com.garganttua.core.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.garganttua.core.reflection.annotations.Reflected;

/**
 * A cache in the memory of one node: at most {@link CacheSpec#maxEntries()} entries, the least recently used going
 * first, each living {@link CacheSpec#ttl()} at most. The default implementation — and the local half of the
 * distributed ones, which keep their copy in one.
 *
 * <p>
 * Thread-safe: reads and writes go through one lock, short and never held while a loader runs. Two requests
 * missing the same key at once both load it; the second write wins, which is harmless for a cache.
 * </p>
 *
 * <p>
 * <b>A load overtaken by an invalidation is not cached.</b> A request reads the old value from the source, a write
 * then invalidates the key, and the request caches what it read: the cache would serve a value the write had
 * replaced until its time to live. {@link #get(Object, Supplier)} therefore caches what it loaded only when no
 * invalidation of this cache happened while it loaded.
 * </p>
 */
@Reflected
public final class LocalCache<K, V> implements ICache<K, V> {

    private record Slot<V>(V value, long expiresAtNanos) {
    }

    private final String name;
    private final long ttlNanos;
    private final LongSupplier clock;
    private final Map<String, Slot<V>> entries;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong invalidations = new AtomicLong();
    /** Moves on every removal — local or applied from another node — so that a load can tell it was overtaken. */
    private final AtomicLong epoch = new AtomicLong();

    public LocalCache(String name, CacheSpec spec) {
        this(name, spec, System::nanoTime);
    }

    /** With the clock it reads time from — {@link System#nanoTime()} outside of tests. */
    public LocalCache(String name, CacheSpec spec, LongSupplier clock) {
        this.name = Objects.requireNonNull(name, "Cache name cannot be null");
        Objects.requireNonNull(spec, "Cache spec cannot be null");
        this.ttlNanos = spec.ttl().toNanos();
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
        int max = spec.maxEntries();
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Slot<V>> eldest) {
                return size() > max;
            }
        };
    }

    @Override
    public String name() {
        return this.name;
    }

    @Override
    public Optional<V> get(K key) {
        String id = String.valueOf(key);
        Slot<V> entry;
        synchronized (this.entries) {
            entry = this.entries.get(id);
            if (entry != null && entry.expiresAtNanos() - this.clock.getAsLong() <= 0) {
                this.entries.remove(id);
                entry = null;
            }
        }
        if (entry == null) {
            this.misses.incrementAndGet();
            return Optional.empty();
        }
        this.hits.incrementAndGet();
        return Optional.of(entry.value());
    }

    @Override
    public V get(K key, Supplier<? extends V> loader) {
        Optional<V> cached = get(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        long before = this.epoch.get();
        V loaded = loader.get();
        if (loaded != null && this.epoch.get() == before) {
            put(key, loaded);
        }
        return loaded;
    }

    @Override
    public void put(K key, V value) {
        if (value == null) {
            invalidate(key);
            return;
        }
        Slot<V> entry = new Slot<>(value, this.clock.getAsLong() + this.ttlNanos);
        synchronized (this.entries) {
            this.entries.put(String.valueOf(key), entry);
        }
    }

    @Override
    public void invalidate(K key) {
        this.epoch.incrementAndGet();
        synchronized (this.entries) {
            this.entries.remove(String.valueOf(key));
        }
        this.invalidations.incrementAndGet();
    }

    @Override
    public void invalidateAll() {
        this.epoch.incrementAndGet();
        synchronized (this.entries) {
            this.entries.clear();
        }
        this.invalidations.incrementAndGet();
    }

    @Override
    public CacheStats stats() {
        int size;
        synchronized (this.entries) {
            size = this.entries.size();
        }
        return new CacheStats(this.hits.get(), this.misses.get(), this.invalidations.get(), size);
    }

    /** Removes {@code id} without counting it — for an implementation applying an invalidation another node sent. */
    public void evict(String id) {
        this.epoch.incrementAndGet();
        synchronized (this.entries) {
            this.entries.remove(id);
        }
    }

    /** Removes every entry without counting it — for an implementation that can no longer vouch for them. */
    public void evictAll() {
        this.epoch.incrementAndGet();
        synchronized (this.entries) {
            this.entries.clear();
        }
    }
}
