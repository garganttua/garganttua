package com.garganttua.core.cache.redis;

import java.util.Objects;

import com.garganttua.core.cache.CacheException;
import com.garganttua.core.cache.CacheSpec;
import com.garganttua.core.cache.ICache;
import com.garganttua.core.cache.ICacheFactory;
import com.garganttua.core.cache.annotations.CacheFactory;
import com.garganttua.core.reflection.annotations.Reflected;

import redis.clients.jedis.JedisPool;

/** Creates {@link RedisCache}s on one redis — {@code localhost:6379} when built without a pool. */
@Reflected
@CacheFactory(type = RedisCache.class)
public final class RedisCacheFactory implements ICacheFactory {

    private final JedisPool pool;

    public RedisCacheFactory() {
        this(new JedisPool());
    }

    public RedisCacheFactory(JedisPool pool) {
        this.pool = Objects.requireNonNull(pool, "Jedis pool cannot be null");
    }

    @Override
    public <K, V> ICache<K, V> createCache(String name, CacheSpec spec) throws CacheException {
        return new RedisCache<>(name, spec, this.pool);
    }
}
