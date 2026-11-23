package com.garganttua.core.cache;

/**
 * Creates the caches of one implementation. A factory class carries {@code @CacheFactory(type = …)} to be found by
 * a {@code CacheManagerBuilder} scanning its packages, or is registered with {@code withFactory(type, factory)}.
 */
// A factory contract that happens to have one method today; not meant as a lambda target.
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface ICacheFactory {

    /**
     * Creates a cache.
     *
     * @param name the cache's identifier
     * @param spec how it holds its entries
     * @return the cache
     * @throws CacheException when the cache cannot be created
     */
    <K, V> ICache<K, V> createCache(String name, CacheSpec spec) throws CacheException;
}
