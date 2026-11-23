/**
 * Distributed cache implementations over redis: {@link com.garganttua.core.cache.redis.RedisCache} (values shared
 * in redis) and {@link com.garganttua.core.cache.redis.RedisNearCache} (a local copy per node, kept coherent by
 * invalidations broadcast over redis).
 */
package com.garganttua.core.cache.redis;
