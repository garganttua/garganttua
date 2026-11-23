package com.garganttua.core.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LocalCache — bounded, expiring, counted")
class LocalCacheTest {

    private final AtomicLong now = new AtomicLong();

    private LocalCache<String, String> cache(int max, Duration ttl) {
        return new LocalCache<>("test", CacheSpec.of().maxEntries(max).withTtl(ttl), this.now::get);
    }

    @Test
    @DisplayName("a value put is read back, and counted as a hit; an absent one as a miss")
    void hitAndMiss() {
        LocalCache<String, String> cache = cache(10, Duration.ofSeconds(60));
        cache.put("a", "A");

        assertEquals(Optional.of("A"), cache.get("a"));
        assertEquals(Optional.empty(), cache.get("b"));
        assertEquals(new CacheStats(1, 1, 0, 1), cache.stats());
    }

    @Test
    @DisplayName("an entry does not outlive its time to live")
    void expires() {
        LocalCache<String, String> cache = cache(10, Duration.ofSeconds(30));
        cache.put("a", "A");

        this.now.addAndGet(Duration.ofSeconds(29).toNanos());
        assertEquals(Optional.of("A"), cache.get("a"));
        this.now.addAndGet(Duration.ofSeconds(1).toNanos());
        assertEquals(Optional.empty(), cache.get("a"));
        assertEquals(0, cache.stats().size(), "an expired entry is dropped when found");
    }

    @Test
    @DisplayName("past its bound, the least recently used entry goes first")
    void leastRecentlyUsedGoes() {
        LocalCache<String, String> cache = cache(2, Duration.ofSeconds(60));
        cache.put("a", "A");
        cache.put("b", "B");
        cache.get("a");
        cache.put("c", "C");

        assertEquals(Optional.of("A"), cache.get("a"));
        assertEquals(Optional.empty(), cache.get("b"));
        assertEquals(Optional.of("C"), cache.get("c"));
    }

    @Test
    @DisplayName("a loader runs on a miss only, and a null it returns is not cached")
    void loader() {
        LocalCache<String, String> cache = cache(10, Duration.ofSeconds(60));
        AtomicInteger loads = new AtomicInteger();

        assertEquals("A", cache.get("a", () -> { loads.incrementAndGet(); return "A"; }));
        assertEquals("A", cache.get("a", () -> { loads.incrementAndGet(); return "other"; }));
        assertNull(cache.get("b", () -> null));
        assertEquals(1, loads.get());
        assertEquals(1, cache.stats().size());
    }

    @Test
    @DisplayName("a load overtaken by an invalidation returns its value but does not cache it")
    void overtakenLoadNotCached() {
        LocalCache<String, String> cache = cache(10, Duration.ofSeconds(60));

        String loaded = cache.get("a", () -> {
            cache.invalidate("a");          // a write lands while the old value is being read
            return "old";
        });

        assertEquals("old", loaded);
        assertEquals(Optional.empty(), cache.get("a"), "the value the write replaced is not served afterwards");
    }

    @Test
    @DisplayName("invalidate removes a key, invalidateAll every key, both counted; put(null) invalidates")
    void invalidation() {
        LocalCache<String, String> cache = cache(10, Duration.ofSeconds(60));
        cache.put("a", "A");
        cache.put("b", "B");
        cache.put("c", "C");

        cache.invalidate("a");
        cache.put("b", null);
        assertEquals(Optional.empty(), cache.get("a"));
        assertEquals(Optional.empty(), cache.get("b"));
        cache.invalidateAll();
        assertEquals(0, cache.stats().size());
        assertEquals(3, cache.stats().invalidations());
    }

    @Test
    @DisplayName("keys are their string form: two keys printing alike are one entry")
    void keysByStringForm() {
        LocalCache<Object, String> cache = new LocalCache<>("test", CacheSpec.of(), this.now::get);
        cache.put(42, "int");

        assertEquals(Optional.of("int"), cache.get("42"));
    }

    @Test
    @DisplayName("hitRate is the share of reads served")
    void hitRate() {
        assertEquals(0.75, new CacheStats(3, 1, 0, 0).hitRate());
        assertTrue(new CacheStats(0, 0, 0, 0).hitRate() == 0d);
    }
}
