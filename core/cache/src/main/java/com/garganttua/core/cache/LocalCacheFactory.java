package com.garganttua.core.cache;

import com.garganttua.core.cache.annotations.CacheFactory;
import com.garganttua.core.reflection.annotations.Reflected;

/** Creates {@link LocalCache}s. */
@Reflected
@CacheFactory(type = LocalCache.class)
public final class LocalCacheFactory implements ICacheFactory {

    @Override
    public <K, V> ICache<K, V> createCache(String name, CacheSpec spec) {
        return new LocalCache<>(name, spec);
    }
}
