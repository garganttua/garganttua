package com.garganttua.core.cache;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Level 0 of the framework's caches — the <b>structure</b>: what depends only on the code and the types (resolved
 * members, field accessors, method invokers, per-type readers and converters), computed once per key and kept for
 * the life of the process.
 *
 * <p>
 * Not an {@link ICache}: nothing here expires, is invalidated or travels. A structure is the same on every node of a
 * version and cannot change while the process runs, so it is local by nature — distributing it would only add a
 * round trip where microseconds are at stake.
 * </p>
 *
 * <ul>
 * <li><b>A hit costs one lookup, never a lock.</b> A present key is read with a plain {@code get}; only a miss
 * computes. (A {@code computeIfAbsent} on every call may lock its bin even when the key is there — under load, the
 * threads of a hot path then queue on the same bins.)</li>
 * <li><b>A failure is never kept.</b> A loader that throws stores nothing; the next call tries again and fails the
 * same way, as an uncached resolution would.</li>
 * <li><b>Two first calls may both compute</b>; one value wins and both get it. A loader must therefore be a pure
 * function of its key — which is what makes a value a structure.</li>
 * <li><b>Observable.</b> Each cache has a name; {@link #sizes()} reports how many entries every live cache holds, by
 * name — the one thing worth watching in a cache that never shrinks.</li>
 * </ul>
 *
 * <p>
 * Keep the key cheap to hash — a record of descriptors and names. Where the key is the Java class itself, a
 * {@link ClassValue} is cheaper still: see {@link ClassStructureCache}.
 * </p>
 *
 * @param <K> the key — a type, or a type and a name
 * @param <V> the structure computed for it
 */
public final class StructureCache<K, V> {

    /** Every live cache, for {@link #sizes()} — weakly, so a cache dropped with its owner is not retained. */
    private static final Set<StructureCache<?, ?>> LIVE = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    /** What computes a structure from its key; it may fail. */
    @FunctionalInterface
    public interface Loader<K, V, E extends Exception> {

        /** {@return the structure of {@code key}} — never null. */
        V load(K key) throws E;
    }

    private final String cacheName;
    private final ConcurrentMap<K, V> values = new ConcurrentHashMap<>();

    private StructureCache(String name) {
        this.cacheName = Objects.requireNonNull(name, "Structure cache name cannot be null");
    }

    /** {@return a new, empty structure cache named {@code name}} — e.g. {@code "reflection.fields-by-name"}. */
    public static <K, V> StructureCache<K, V> named(String name) {
        StructureCache<K, V> cache = new StructureCache<>(name);
        LIVE.add(cache);
        return cache;
    }

    /** {@return the structure of {@code key}}, computed by {@code loader} the first time; a failure is not kept. */
    public <E extends Exception> V get(K key, Loader<? super K, ? extends V, E> loader) throws E {
        V known = this.values.get(key);
        if (known != null) {
            return known;
        }
        V loaded = Objects.requireNonNull(loader.load(key), "A structure loader cannot return null");
        V raced = this.values.putIfAbsent(key, loaded);
        return raced != null ? raced : loaded;
    }

    /** {@return the structure of {@code key} if it was computed}, without computing it. */
    public Optional<V> peek(K key) {
        return Optional.ofNullable(this.values.get(key));
    }

    public String name() {
        return this.cacheName;
    }

    public int size() {
        return this.values.size();
    }

    /** {@return the number of entries of every live structure cache, summed by name}. */
    public static Map<String, Integer> sizes() {
        Map<String, Integer> sizes = new TreeMap<>();
        synchronized (LIVE) {
            for (StructureCache<?, ?> cache : LIVE) {
                sizes.merge(cache.cacheName, cache.size(), Integer::sum);
            }
        }
        ClassStructureCache.addSizes(sizes);
        return sizes;
    }
}
