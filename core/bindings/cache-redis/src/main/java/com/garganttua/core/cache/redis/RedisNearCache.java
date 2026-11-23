package com.garganttua.core.cache.redis;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.garganttua.core.cache.CacheSpec;
import com.garganttua.core.cache.CacheStats;
import com.garganttua.core.cache.ICache;
import com.garganttua.core.cache.LocalCache;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.annotations.Reflected;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * A cache each node keeps in its own memory, kept coherent by invalidations broadcast over redis: a node that
 * invalidates a key publishes it, and every node subscribed drops its copy. Values never cross the network — no
 * codec, nothing to serialize — and a read that hits costs no round trip.
 *
 * <p>
 * <b>A node that is not subscribed does not use its copy.</b> It cannot know what it missed: until its
 * subscription is (re)established, every read goes to the source and nothing is cached. When the subscription
 * comes back, the copy is emptied first — the invalidations sent meanwhile are lost — then used again. A publish
 * that fails is logged as an error: the other nodes may serve the old value for at most the time to live.
 * </p>
 *
 * <p>
 * The caches of one {@link RedisNearCacheFactory} share one subscription, hence one connection of the pool, however
 * many they are ({@link RedisInvalidations}). A cache opened alone by {@link #open} has its own; {@link #close()}
 * stops it.
 * </p>
 */
@Reflected
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class RedisNearCache<K, V> implements ICache<K, V>, AutoCloseable {

    private static final Logger log = Logger.getLogger(RedisNearCache.class);

    /** A message invalidating one key: the prefix, then the key's string form. */
    static final String KEY = "k:";
    /** The message invalidating every key. */
    static final String ALL = "*";

    private final String name;
    private final JedisPool pool;
    private final String channel;
    private final long ttlMillis;
    private final LocalCache<K, V> local;
    private final AtomicLong bypassed = new AtomicLong();
    private final RedisInvalidations invalidations;
    private final boolean ownsInvalidations;

    /**
     * Creates a cache with its own subscription to its invalidations — it is used only once subscribed. Several
     * caches of one node share theirs when created by a {@link RedisNearCacheFactory}.
     *
     * @param name the cache's name, which names its redis channel
     * @param spec its bound and time to live
     * @param pool the redis its invalidations travel on
     * @return the cache, listening
     */
    public static <K, V> RedisNearCache<K, V> open(String name, CacheSpec spec, JedisPool pool) {
        return new RedisNearCache<K, V>(name, spec, RedisInvalidations.start(pool), true).listening();
    }

    /** A cache listening on a subscription it shares with the other caches of its factory. */
    static <K, V> RedisNearCache<K, V> open(String name, CacheSpec spec, RedisInvalidations shared) {
        return new RedisNearCache<K, V>(name, spec, shared, false).listening();
    }

    private RedisNearCache(String name, CacheSpec spec, RedisInvalidations invalidations, boolean owns) {
        this.name = Objects.requireNonNull(name, "Cache name cannot be null");
        this.invalidations = Objects.requireNonNull(invalidations, "Invalidations cannot be null");
        this.pool = invalidations.connections();
        this.ownsInvalidations = owns;
        this.channel = RedisKeys.channel(name);
        this.ttlMillis = spec.ttl().toMillis();
        this.local = new LocalCache<>(name, spec);
    }

    private RedisNearCache<K, V> listening() {
        this.invalidations.register(this.channel, new RedisInvalidations.Listener() {
            @Override
            public void onMessage(String message) {
                if (ALL.equals(message)) {
                    RedisNearCache.this.local.evictAll();
                } else if (message.startsWith(KEY)) {
                    RedisNearCache.this.local.evict(message.substring(KEY.length()));
                }
            }

            @Override
            public void onResubscribed() {
                RedisNearCache.this.local.evictAll();
            }
        });
        return this;
    }

    @Override
    public String name() {
        return this.name;
    }

    /** {@return whether this node is subscribed to the invalidations, hence uses its copy} */
    public boolean isSubscribed() {
        return this.invalidations.isSubscribed();
    }

    @Override
    public Optional<V> get(K key) {
        if (!isSubscribed()) {
            this.bypassed.incrementAndGet();
            return Optional.empty();
        }
        return this.local.get(key);
    }

    @Override
    public V get(K key, Supplier<? extends V> loader) {
        if (!isSubscribed()) {
            this.bypassed.incrementAndGet();
            return loader.get();
        }
        return this.local.get(key, loader);
    }

    @Override
    public void put(K key, V value) {
        if (isSubscribed()) {
            this.local.put(key, value);
        }
    }

    @Override
    public void invalidate(K key) {
        this.local.invalidate(key);
        publish(KEY + key);
    }

    @Override
    public void invalidateAll() {
        this.local.invalidateAll();
        publish(ALL);
    }

    @Override
    public CacheStats stats() {
        CacheStats copy = this.local.stats();
        return new CacheStats(copy.hits(), copy.misses() + this.bypassed.get(), copy.invalidations(), copy.size());
    }

    @Override
    public void close() {
        this.invalidations.unregister(this.channel);
        if (this.ownsInvalidations) {
            this.invalidations.close();
        }
    }

    private void publish(String message) {
        try (Jedis jedis = this.pool.getResource()) {
            jedis.publish(this.channel, message);
        } catch (RuntimeException e) {
            log.error("Cache '{}': invalidation '{}' could not be broadcast — other nodes may serve the old value for "
                    + "at most {} ms ({})", this.name, message, this.ttlMillis, e.getMessage());
        }
    }
}
