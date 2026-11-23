package com.garganttua.core.cache;

import java.util.Objects;

import com.garganttua.core.reflection.IClass;

/**
 * A cache's qualified name: the implementation that holds it and its identifier — {@code TypeName::name}, as a
 * {@code MutexName}. Two declarations naming the same cache share it.
 *
 * @param type the implementation class, which selects its factory — an {@link ICache}
 * @param name the cache's identifier, non-blank
 */
public record CacheName(IClass<?> type, String name) {

    /** The separator of the string form. */
    public static final String SEPARATOR = "::";

    public CacheName {
        Objects.requireNonNull(type, "Cache type cannot be null");
        if (!IClass.getClass(ICache.class).isAssignableFrom(type)) {
            throw new IllegalArgumentException(type.getName() + " is not an ICache");
        }
        Objects.requireNonNull(name, "Cache name cannot be null");
        name = name.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Cache name cannot be empty");
        }
    }

    @Override
    public String toString() {
        return this.type.getSimpleName() + SEPARATOR + this.name;
    }
}
