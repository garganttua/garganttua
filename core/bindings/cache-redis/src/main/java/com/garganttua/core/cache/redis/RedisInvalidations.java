package com.garganttua.core.cache.redis;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.garganttua.core.observability.Logger;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

/**
 * The invalidations of every {@link RedisNearCache} of one node, on <b>one</b> subscription: a pattern on every
 * cache channel, held on a single connection of the pool, whatever the number of caches.
 *
 * <p>
 * A subscription holds its connection for as long as it lives. One per cache would take as many connections out
 * of the pool as there are caches — eight caches drain a default pool, and every redis call sharing it (the other
 * caches, the locks) then waits forever for a connection.
 * </p>
 *
 * <p>
 * While the subscription is down, {@link #isSubscribed()} is false and every cache reads from its source. When it
 * comes back, every registered copy is emptied before it is used again: what was invalidated meanwhile is unknown.
 * The subscription runs on a daemon thread, reconnecting with a growing pause (100 ms to 5 s), until closed.
 * </p>
 */
final class RedisInvalidations implements AutoCloseable {

    private static final Logger log = Logger.getLogger(RedisInvalidations.class);

    private static final String PATTERN = RedisKeys.PREFIX + "*" + RedisKeys.CHANNEL_SUFFIX;
    private static final long FIRST_PAUSE_MILLIS = 100L;
    private static final long MAX_PAUSE_MILLIS = 5_000L;

    /** What a cache does with the messages of its channel. */
    interface Listener {

        /** One message of its channel. */
        void onMessage(String message);

        /** The subscription is (re)established: what it missed is unknown, its copy must go. */
        void onResubscribed();
    }

    private final JedisPool pool;
    private final Map<String, Listener> listeners = new ConcurrentHashMap<>();
    private final Subscriber subscriber = new Subscriber();
    private final Thread thread;
    private volatile boolean subscribed;
    private volatile boolean closed;

    /** {@return the invalidations of {@code pool}, listening} */
    static RedisInvalidations start(JedisPool pool) {
        RedisInvalidations invalidations = new RedisInvalidations(pool);
        invalidations.thread.start();
        return invalidations;
    }

    private RedisInvalidations(JedisPool pool) {
        this.pool = Objects.requireNonNull(pool, "Jedis pool cannot be null");
        this.thread = new Thread(this::listen, "garganttua-cache-invalidations");
        this.thread.setDaemon(true);
    }

    /** {@return the pool its subscription holds a connection of — the caches publish on it} */
    JedisPool connections() {
        return this.pool;
    }

    boolean isSubscribed() {
        return this.subscribed;
    }

    /** Delivers the messages of {@code channel} to {@code listener} from now on. */
    void register(String channel, Listener listener) {
        this.listeners.put(channel, listener);
    }

    void unregister(String channel) {
        this.listeners.remove(channel);
    }

    @Override
    public void close() {
        this.closed = true;
        this.subscribed = false;
        if (this.subscriber.isSubscribed()) {
            this.subscriber.punsubscribe();
        }
        this.thread.interrupt();
    }

    /** Subscribes, and resubscribes after any loss, until closed. */
    private void listen() {
        long pause = FIRST_PAUSE_MILLIS;
        while (!this.closed) {
            try (Jedis jedis = this.pool.getResource()) {
                jedis.psubscribe(this.subscriber, PATTERN);   // blocks while subscribed
                pause = FIRST_PAUSE_MILLIS;
            } catch (RuntimeException e) {
                if (!this.closed) {
                    log.warn("Cache invalidations: not subscribed, every near cache reads from its source until it "
                            + "is ({})", e.getMessage());
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
        public void onPSubscribe(String pattern, int subscribedChannels) {
            RedisInvalidations.this.listeners.values().forEach(Listener::onResubscribed);
            RedisInvalidations.this.subscribed = true;
            log.debug("Cache invalidations: subscribed to {}", pattern);
        }

        @Override
        public void onPMessage(String pattern, String channel, String message) {
            Listener listener = RedisInvalidations.this.listeners.get(channel);
            if (listener != null) {
                listener.onMessage(message);
            }
        }
    }
}
