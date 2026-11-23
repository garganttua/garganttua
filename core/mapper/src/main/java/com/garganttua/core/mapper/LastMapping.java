package com.garganttua.core.mapper;

import java.util.concurrent.atomic.AtomicLong;

import com.garganttua.core.reflection.IClass;

/**
 * The pair a {@link Mapper} mapped last, and its configuration: a list read maps the same pair for every element, so
 * the configuration table is looked up once per list instead of once per element.
 *
 * <p>
 * A version, bumped whenever a configuration is recorded, keeps it honest: the remembered pair is used only while its
 * version is current, so a lookup that raced with a recording is never reused.
 * </p>
 */
final class LastMapping {

    private record Entry(IClass<?> source, IClass<?> destination, CachedMappingConfiguration config, long version) {
    }

    private volatile Entry last;
    private final AtomicLong version = new AtomicLong();

    /** How the mapper looks a pair up in its table. */
    @FunctionalInterface
    interface Lookup {
        CachedMappingConfiguration find(IClass<?> source, IClass<?> destination) throws MapperException;
    }

    /** {@return the configuration of the pair}: the remembered one if it is this very pair and still current. */
    // CompareObjectsWithEquals: identity is enough to recognise the very descriptors seen last; an equal but distinct
    // one just takes the table path.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    CachedMappingConfiguration get(IClass<?> source, IClass<?> destination, Lookup lookup) throws MapperException {
        long current = this.version.get();   // read before the lookup: a recording after it voids what we remember
        Entry entry = this.last;
        if (entry != null && entry.version() == current && entry.source() == source
                && entry.destination() == destination) {
            return entry.config();
        }
        CachedMappingConfiguration found = lookup.find(source, destination);
        this.last = new Entry(source, destination, found, current);
        return found;
    }

    /** A configuration was recorded: whatever is remembered is no longer current. */
    void recorded() {
        this.version.incrementAndGet();
    }
}
