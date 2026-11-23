package com.garganttua.core.cache;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * A cache, whatever its implementation: in memory on one node, shared in a store, or local with invalidation
 * broadcast to every node. The framework and applications read through this interface only; the implementation
 * is chosen by declaration ({@link CacheName#type()}) and can change by configuration.
 *
 * <p>
 * <b>Keys</b> are identified by their {@link String#valueOf(Object) string form}, the only form a distributed
 * implementation can carry: two keys with the same string form are the same entry.
 * </p>
 *
 * <p>
 * <b>Never wrong, at worst slower.</b> An implementation that cannot guarantee it has seen every invalidation —
 * a store that does not answer, a broadcast it is no longer subscribed to — behaves as an empty cache: reads miss
 * and go to the source. An entry never outlives the {@link CacheSpec#ttl() time to live} of its spec, which bounds
 * what a lost invalidation can cost.
 * </p>
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public interface ICache<K, V> {

    /** {@return the cache's name, unique within its implementation} */
    String name();

    /** {@return the value cached under {@code key}, empty on a miss} */
    Optional<V> get(K key);

    /**
     * The value cached under {@code key}, or the one {@code loader} produces — then cached. A {@code null} from the
     * loader is returned and not cached.
     *
     * @param key    the key
     * @param loader computes the value on a miss
     * @return the cached or loaded value, {@code null} when the loader returned {@code null}
     */
    V get(K key, Supplier<? extends V> loader);

    /** Caches {@code value} under {@code key}; a {@code null} value invalidates the key instead. */
    void put(K key, V value);

    /** Removes {@code key} — on every node, for a distributed implementation, before returning. */
    void invalidate(K key);

    /** Removes every entry — on every node, for a distributed implementation, before returning. */
    void invalidateAll();

    /** {@return the counters of this cache, on this node} */
    CacheStats stats();
}
