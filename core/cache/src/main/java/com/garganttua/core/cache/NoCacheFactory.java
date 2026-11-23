package com.garganttua.core.cache;

import com.garganttua.core.cache.annotations.CacheFactory;
import com.garganttua.core.reflection.annotations.Reflected;

/** Creates {@link NoCache}s. */
@Reflected
@CacheFactory(type = NoCache.class)
public final class NoCacheFactory implements ICacheFactory {

    @Override
    public <K, V> ICache<K, V> createCache(String name, CacheSpec spec) {
        return new NoCache<>(name);
    }
}
