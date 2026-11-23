package com.garganttua.core.cache;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * A {@link StructureCache} keyed by the Java class itself, on a {@link ClassValue}: the value is attached to the
 * class, so a hit hashes nothing and takes no lock — about three times cheaper than a concurrent map on the JVM, and
 * still cheaper in a native image. The loader cannot fail; concurrent first calls agree on one value.
 *
 * <p>
 * This is the one place a raw {@code Class} is the key: the bridge from the JVM's classes to the framework's
 * {@code IClass} descriptors lives on it.
 * </p>
 *
 * @param <V> the structure computed for a class
 */
public final class ClassStructureCache<V> {

    private static final Set<ClassStructureCache<?>> LIVE = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    private final String cacheName;
    private final LongAdder computed = new LongAdder();
    private final ClassValue<V> values;

    private ClassStructureCache(String name, Function<Class<?>, V> loader) {
        this.cacheName = Objects.requireNonNull(name, "Structure cache name cannot be null");
        Objects.requireNonNull(loader, "Structure loader cannot be null");
        this.values = new ClassValue<>() {
            @Override
            protected V computeValue(Class<?> type) {
                ClassStructureCache.this.computed.increment();
                return loader.apply(type);
            }
        };
    }

    /** {@return a new structure cache named {@code name}, computing each class's value with {@code loader}}. */
    public static <V> ClassStructureCache<V> named(String name, Function<Class<?>, V> loader) {
        ClassStructureCache<V> cache = new ClassStructureCache<>(name, loader);
        LIVE.add(cache);
        return cache;
    }

    /** {@return the structure of {@code type}} — possibly null, if the loader says so for this class. */
    public V get(Class<?> type) {
        return this.values.get(type);
    }

    public String name() {
        return this.cacheName;
    }

    /** {@return how many classes this cache computed a value for} — an upper bound when first calls raced. */
    public long size() {
        return this.computed.sum();
    }

    static void addSizes(Map<String, Integer> sizes) {
        synchronized (LIVE) {
            for (ClassStructureCache<?> cache : LIVE) {
                sizes.merge(cache.cacheName, (int) Math.min(Integer.MAX_VALUE, cache.size()), Integer::sum);
            }
        }
    }
}
