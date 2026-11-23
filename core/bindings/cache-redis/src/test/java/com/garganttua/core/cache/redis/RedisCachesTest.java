package com.garganttua.core.cache.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.core.cache.CacheException;
import com.garganttua.core.cache.CacheSpec;
import com.garganttua.core.cache.ICache;
import com.garganttua.core.cache.ICacheCodec;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;

/**
 * The redis caches, without redis (they are absent, never wrong) and against a real one — set
 * {@code -Dgarganttua.test.redis=host:port}, e.g. a throwaway {@code docker run -p 6391:6379 redis:7-alpine}.
 */
@DisplayName("Redis caches")
class RedisCachesTest {

    private static final ICacheCodec<String> UTF8 = new ICacheCodec<>() {
        @Override public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        @Override public String decode(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
    };

    private static final CacheSpec SPEC = CacheSpec.of().codec(UTF8);

    private static String unique() {
        return "test-" + UUID.randomUUID();
    }

    /** A pool on a port nothing listens on. */
    private static JedisPool unreachable() {
        return new JedisPool("127.0.0.1", 1);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    @Nested
    @DisplayName("without redis")
    class Unreachable {

        @Test
        @DisplayName("RedisCache is absent: every read goes to the source, writes are dropped, nothing throws")
        void sharedAbsent() {
            RedisCache<String, String> cache = new RedisCache<>(unique(), SPEC, unreachable());
            AtomicInteger loads = new AtomicInteger();

            cache.put("a", "A");
            cache.invalidate("a");
            assertEquals("A", cache.get("a", () -> { loads.incrementAndGet(); return "A"; }));
            assertEquals("A", cache.get("a", () -> { loads.incrementAndGet(); return "A"; }));
            assertEquals(2, loads.get());
        }

        @Test
        @DisplayName("RedisCache needs a codec")
        void codecRequired() {
            CacheException refused = assertThrows(CacheException.class,
                    () -> new RedisCache<String, String>("x", CacheSpec.of(), unreachable()));
            assertTrue(refused.getMessage().contains("needs a codec"), refused.getMessage());
        }

        @Test
        @DisplayName("RedisNearCache never subscribed never uses its copy")
        void nearAbsent() {
            try (RedisNearCache<String, String> cache = RedisNearCache.open(unique(), CacheSpec.of(), unreachable())) {
                AtomicInteger loads = new AtomicInteger();

                cache.get("a", () -> { loads.incrementAndGet(); return "A"; });
                cache.put("a", "A");
                assertEquals(Optional.empty(), cache.get("a"));
                cache.get("a", () -> { loads.incrementAndGet(); return "A"; });

                assertFalse(cache.isSubscribed());
                assertEquals(2, loads.get());
            }
        }
    }

    @Nested
    @DisplayName("against redis")
    class AgainstRedis {

        private JedisPool node() {
            String address = System.getProperty("garganttua.test.redis");
            assumeTrue(address != null && !address.isBlank(), "-Dgarganttua.test.redis=host:port not set");
            String[] parts = address.split(":");
            return new JedisPool(parts[0], Integer.parseInt(parts[1]));
        }

        @Test
        @DisplayName("ten near caches of one factory hold one connection: a pool of two still serves them all")
        void nearCachesShareOneSubscription() throws Exception {
            JedisPool remote = node();
            String[] parts = System.getProperty("garganttua.test.redis").split(":");
            JedisPoolConfig small = new JedisPoolConfig();
            small.setMaxTotal(2);
            small.setMaxWaitMillis(2_000);
            try (JedisPool pool = new JedisPool(small, parts[0], Integer.parseInt(parts[1]));
                    RedisNearCacheFactory here = new RedisNearCacheFactory(pool);
                    RedisNearCacheFactory there = new RedisNearCacheFactory(remote)) {
                List<ICache<String, String>> caches = new ArrayList<>();
                List<ICache<String, String>> remotes = new ArrayList<>();
                String prefix = unique();
                for (int i = 0; i < 10; i++) {
                    caches.add(here.createCache(prefix + i, CacheSpec.of()));
                    remotes.add(there.createCache(prefix + i, CacheSpec.of()));
                }
                for (ICache<String, String> cache : caches) {
                    RedisNearCache<String, String> near = (RedisNearCache<String, String>) cache;
                    await(near::isSubscribed, "subscribed");
                    cache.put("k", "v");
                }
                remotes.get(7).invalidate("k");
                ICache<String, String> seventh = caches.get(7);
                await(() -> seventh.get("k").isEmpty(), "the invalidation of the seventh");
                assertEquals(Optional.of("v"), caches.get(6).get("k"), "the others keep their copy");
            }
        }

        @Test
        @DisplayName("RedisCache: one value for every node, invalidated for all, expiring after its time to live")
        void shared() throws InterruptedException {
            String name = unique();
            RedisCache<String, String> one = new RedisCache<>(name, SPEC.withTtl(Duration.ofSeconds(1)), node());
            RedisCache<String, String> two = new RedisCache<>(name, SPEC.withTtl(Duration.ofSeconds(1)), node());

            one.put("a", "A");
            assertEquals(Optional.of("A"), two.get("a"), "written on one node, read on another");
            two.invalidate("a");
            assertEquals(Optional.empty(), one.get("a"), "invalidated on one node, gone for the other");

            one.put("b", "B");
            one.put("c", "C");
            two.invalidateAll();
            assertEquals(Optional.empty(), one.get("b"));
            assertEquals(Optional.empty(), one.get("c"));

            one.put("d", "D");
            Thread.sleep(1_200);
            assertEquals(Optional.empty(), two.get("d"), "expired in redis");
        }

        @Test
        @DisplayName("RedisNearCache: a key invalidated on one node is dropped from the copy of every other")
        void nearInvalidationBroadcast() throws InterruptedException {
            String name = unique();
            try (RedisNearCache<String, String> one = RedisNearCache.open(name, CacheSpec.of(), node());
                    RedisNearCache<String, String> two = RedisNearCache.open(name, CacheSpec.of(), node())) {
                await(() -> one.isSubscribed() && two.isSubscribed(), "both nodes subscribed");
                one.put("a", "A");
                one.put("b", "B");
                assertEquals(Optional.of("A"), one.get("a"));

                two.invalidate("a");
                await(() -> one.get("a").isEmpty(), "node one dropping 'a'");
                assertEquals(Optional.of("B"), one.get("b"), "only the invalidated key");

                two.invalidateAll();
                await(() -> one.get("b").isEmpty(), "node one dropping everything");
            }
        }

        @Test
        @DisplayName("RedisNearCache: after losing its subscription, a node starts again from an empty copy")
        void nearResubscribeFlushes() throws InterruptedException {
            try (RedisNearCache<String, String> one = RedisNearCache.open(unique(), CacheSpec.of(), node());
                    JedisPool admin = node(); Jedis jedis = admin.getResource()) {
                await(one::isSubscribed, "subscribed");
                one.put("a", "A");
                assertEquals(1, one.stats().size());

                jedis.sendCommand(Protocol.Command.CLIENT, "KILL", "TYPE", "pubsub");   // the connection drops

                await(() -> one.isSubscribed() && one.stats().size() == 0, "resubscribed with an empty copy");
                one.put("b", "B");
                assertEquals(Optional.of("B"), one.get("b"), "used again once subscribed");
            }
        }
    }
}
