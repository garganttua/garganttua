package com.garganttua.core.cache.redis;

import java.util.Objects;

import com.garganttua.core.cache.CacheSpec;
import com.garganttua.core.cache.ICache;
import com.garganttua.core.cache.ICacheFactory;
import com.garganttua.core.cache.annotations.CacheFactory;
import com.garganttua.core.reflection.annotations.Reflected;

import redis.clients.jedis.JedisPool;

/**
 * Creates {@link RedisNearCache}s whose invalidations travel on one redis — {@code localhost:6379} by default. Its
 * caches share one subscription, held on one connection of the pool: the pool must allow one connection more than
 * the calls it serves.
 */
@Reflected
@CacheFactory(type = RedisNearCache.class)
public final class RedisNearCacheFactory implements ICacheFactory, AutoCloseable {

    private final JedisPool pool;
    private RedisInvalidations invalidations;

    public RedisNearCacheFactory() {
        this(new JedisPool());
    }

    public RedisNearCacheFactory(JedisPool pool) {
        this.pool = Objects.requireNonNull(pool, "Jedis pool cannot be null");
    }

    @Override
    public <K, V> ICache<K, V> createCache(String name, CacheSpec spec) {
        return RedisNearCache.open(name, spec, shared());
    }

    /** {@return the subscription its caches share, started with the first cache} */
    private synchronized RedisInvalidations shared() {
        if (this.invalidations == null) {
            this.invalidations = RedisInvalidations.start(this.pool);
        }
        return this.invalidations;
    }

    /**
     * Stops the subscription its caches share: they read from their source from then on, as do the caches it
     * creates afterwards.
     */
    @Override
    public synchronized void close() {
        if (this.invalidations != null) {
            this.invalidations.close();
        }
    }
}
