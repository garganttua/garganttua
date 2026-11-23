package com.garganttua.core.cache;

/**
 * Resolves caches by name, through the factory of their implementation. The same name always resolves to the same
 * cache, so every declaration that names it shares its entries.
 */
// A manager contract that happens to have one method today; not meant as a lambda target.
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface ICacheManager {

    /**
     * The cache {@code name}, created with {@code spec} the first time it is asked for.
     *
     * @param name the cache's implementation and identifier
     * @param spec how it holds its entries — used at creation only
     * @return the cache
     * @throws CacheException when no factory serves {@code name.type()}, or the factory fails
     */
    <K, V> ICache<K, V> cache(CacheName name, CacheSpec spec) throws CacheException;
}
