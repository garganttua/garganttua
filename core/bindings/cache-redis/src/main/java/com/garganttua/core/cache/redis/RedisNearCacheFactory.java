package com.garganttua.core.cache.redis;

import java.util.Objects;

import com.garganttua.core.cache.CacheSpec;
import com.garganttua.core.cache.ICache;
import com.garganttua.core.cache.ICacheFactory;
import com.garganttua.core.cache.annotations.CacheFactory;
import com.garganttua.core.reflection.annotations.Reflected;

import redis.clients.jedis.JedisPool;

/** Creates {@link RedisNearCache}s whose invalidations travel on one redis — {@code localhost:6379} by default. */
@Reflected
@CacheFactory(type = RedisNearCache.class)
public final class RedisNearCacheFactory implements ICacheFactory {

    private final JedisPool pool;

    public RedisNearCacheFactory() {
        this(new JedisPool());
    }

    public RedisNearCacheFactory(JedisPool pool) {
        this.pool = Objects.requireNonNull(pool, "Jedis pool cannot be null");
    }

    @Override
    public <K, V> ICache<K, V> createCache(String name, CacheSpec spec) {
        return RedisNearCache.open(name, spec, this.pool);
    }
}
