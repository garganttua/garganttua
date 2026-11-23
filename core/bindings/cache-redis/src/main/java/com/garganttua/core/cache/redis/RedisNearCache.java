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
import redis.clients.jedis.JedisPubSub;

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
 * The subscription runs on a daemon thread, reconnecting with a growing pause (100 ms to 5 s); {@link #close()}
 * stops it.
 * </p>
 */
@Reflected
public final class RedisNearCache<K, V> implements ICache<K, V>, AutoCloseable {

    private static final Logger log = Logger.getLogger(RedisNearCache.class);

    /** A message invalidating one key: the prefix, then the key's string form. */
    static final String KEY = "k:";
    /** The message invalidating every key. */
    static final String ALL = "*";

    private static final long FIRST_PAUSE_MILLIS = 100L;
    private static final long MAX_PAUSE_MILLIS = 5_000L;

    private final String name;
    private final JedisPool pool;
    private final String channel;
    private final long ttlMillis;
    private final LocalCache<K, V> local;
    private final AtomicLong bypassed = new AtomicLong();
    private final Subscriber subscriber = new Subscriber();
    private final Thread listener;
    private volatile boolean subscribed;
    private volatile boolean closed;

    /**
     * Creates the cache and starts listening to its invalidations — it is used only once subscribed.
     *
     * @param name the cache's name, which names its redis channel
     * @param spec its bound and time to live
     * @param pool the redis its invalidations travel on
     * @return the cache, listening
     */
    public static <K, V> RedisNearCache<K, V> open(String name, CacheSpec spec, JedisPool pool) {
        RedisNearCache<K, V> cache = new RedisNearCache<>(name, spec, pool);
        cache.listener.start();
        return cache;
    }

    private RedisNearCache(String name, CacheSpec spec, JedisPool pool) {
        this.name = Objects.requireNonNull(name, "Cache name cannot be null");
        this.pool = Objects.requireNonNull(pool, "Jedis pool cannot be null");
        this.channel = RedisKeys.channel(name);
        this.ttlMillis = spec.ttl().toMillis();
        this.local = new LocalCache<>(name, spec);
        this.listener = new Thread(this::listen, "garganttua-cache-" + name + "-invalidations");
        this.listener.setDaemon(true);
    }

    @Override
    public String name() {
        return this.name;
    }

    /** {@return whether this node is subscribed to the invalidations, hence uses its copy} */
    public boolean isSubscribed() {
        return this.subscribed;
    }

    @Override
    public Optional<V> get(K key) {
        if (!this.subscribed) {
            this.bypassed.incrementAndGet();
            return Optional.empty();
        }
        return this.local.get(key);
    }

    @Override
    public V get(K key, Supplier<? extends V> loader) {
        if (!this.subscribed) {
            this.bypassed.incrementAndGet();
            return loader.get();
        }
        return this.local.get(key, loader);
    }

    @Override
    public void put(K key, V value) {
        if (this.subscribed) {
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
        this.closed = true;
        this.subscribed = false;
        if (this.subscriber.isSubscribed()) {
            this.subscriber.unsubscribe();
        }
        this.listener.interrupt();
    }

    private void publish(String message) {
        try (Jedis jedis = this.pool.getResource()) {
            jedis.publish(this.channel, message);
        } catch (RuntimeException e) {
            log.error("Cache '{}': invalidation '{}' could not be broadcast — other nodes may serve the old value for "
                    + "at most {} ms ({})", this.name, message, this.ttlMillis, e.getMessage());
        }
    }

    /** Subscribes, and resubscribes after any loss, until closed. */
    private void listen() {
        long pause = FIRST_PAUSE_MILLIS;
        while (!this.closed) {
            try (Jedis jedis = this.pool.getResource()) {
                jedis.subscribe(this.subscriber, this.channel);   // blocks while subscribed
                pause = FIRST_PAUSE_MILLIS;
            } catch (RuntimeException e) {
                if (!this.closed) {
                    log.warn("Cache '{}': not subscribed to its invalidations, reading from the source until it is "
                            + "({})", this.name, e.getMessage());
                }
            }
            this.subscribed = false;
            if (this.closed) {
                return;
            }
            try {
                Thread.sleep(pause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            pause = Math.min(pause * 2, MAX_PAUSE_MILLIS);
        }
    }

    private final class Subscriber extends JedisPubSub {

        @Override
        public void onSubscribe(String subscribedChannel, int subscribedChannels) {
            // The invalidations sent while this node was not listening are lost: start from an empty copy.
            RedisNearCache.this.local.evictAll();
            RedisNearCache.this.subscribed = true;
            log.debug("Cache '{}': subscribed to its invalidations", RedisNearCache.this.name);
        }

        @Override
        public void onMessage(String messageChannel, String message) {
            if (ALL.equals(message)) {
                RedisNearCache.this.local.evictAll();
            } else if (message.startsWith(KEY)) {
                RedisNearCache.this.local.evict(message.substring(KEY.length()));
            }
        }
    }
}
