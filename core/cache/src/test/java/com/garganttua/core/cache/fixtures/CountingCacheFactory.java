package com.garganttua.core.cache.fixtures;

import java.util.concurrent.atomic.AtomicInteger;

import com.garganttua.core.cache.CacheSpec;
import com.garganttua.core.cache.ICache;
import com.garganttua.core.cache.ICacheFactory;
import com.garganttua.core.cache.LocalCache;
import com.garganttua.core.cache.annotations.CacheFactory;

/** A scanned factory: it creates {@link CountingCache}s and counts them. */
@CacheFactory(type = CountingCache.class)
public class CountingCacheFactory implements ICacheFactory {

    public static final AtomicInteger CREATED = new AtomicInteger();

    @Override
    public <K, V> ICache<K, V> createCache(String name, CacheSpec spec) {
        CREATED.incrementAndGet();
        return new CountingCache<>(new LocalCache<>(name, spec));
    }
}
