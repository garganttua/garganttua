package com.garganttua.core.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Structure caches (L0)")
class StructureCacheTest {

    @Nested
    @DisplayName("StructureCache")
    class Keyed {

        @Test
        @DisplayName("computes a key once, then answers from memory")
        void computesOnce() {
            StructureCache<String, Integer> cache = StructureCache.named("test.once");
            AtomicInteger loads = new AtomicInteger();
            for (int i = 0; i < 5; i++) {
                assertEquals(3, cache.get("abc", key -> {
                    loads.incrementAndGet();
                    return key.length();
                }));
            }
            assertEquals(1, loads.get());
            assertEquals(Optional.of(3), cache.peek("abc"));
            assertEquals(Optional.empty(), cache.peek("other"));
        }

        @Test
        @DisplayName("keeps nothing when the loader fails: the next call loads again, and fails the same way")
        void failureNotKept() {
            StructureCache<String, Integer> cache = StructureCache.named("test.failure");
            AtomicInteger loads = new AtomicInteger();
            StructureCache.Loader<String, Integer, IOException> failing = key -> {
                loads.incrementAndGet();
                throw new IOException("no " + key);
            };
            IOException first = assertThrows(IOException.class, () -> cache.get("k", failing));
            IOException second = assertThrows(IOException.class, () -> cache.get("k", failing));
            assertEquals(first.getMessage(), second.getMessage());
            assertEquals(2, loads.get());
            assertEquals(0, cache.size());
        }

        @Test
        @DisplayName("refuses a null structure")
        void refusesNull() {
            StructureCache<String, Integer> cache = StructureCache.named("test.null");
            assertThrows(NullPointerException.class, () -> cache.get("k", key -> null));
        }

        @Test
        @DisplayName("sixteen threads racing on a key all get the same value")
        void racingFirstCalls() throws Exception {
            StructureCache<String, Object> cache = StructureCache.named("test.race");
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(16);
            try {
                List<Future<Object>> results = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    results.add(pool.submit(() -> {
                        start.await();
                        return cache.get("k", key -> new Object());
                    }));
                }
                start.countDown();
                Set<Object> distinct = new HashSet<>();
                for (Future<Object> result : results) {
                    distinct.add(result.get());
                }
                assertEquals(1, distinct.size());
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("reports its size under its name")
        void sizes() {
            StructureCache<Integer, Integer> cache = StructureCache.named("test.sizes-" + System.nanoTime());
            for (int i = 0; i < 7; i++) {
                cache.get(i, key -> key * 2);
            }
            assertEquals(7, StructureCache.sizes().get(cache.name()));
        }
    }

    @Nested
    @DisplayName("ClassStructureCache")
    class ByClass {

        @Test
        @DisplayName("one value per class, computed once, null allowed")
        void perClass() {
            AtomicInteger loads = new AtomicInteger();
            ClassStructureCache<Object> cache = ClassStructureCache.named("test.classes-" + System.nanoTime(), type -> {
                loads.incrementAndGet();
                return type == Void.class ? null : new Object();
            });
            Object first = cache.get(String.class);
            assertSame(first, cache.get(String.class));
            assertNull(cache.get(Void.class));
            assertNull(cache.get(Void.class));
            assertEquals(2, loads.get());
            assertEquals(2, cache.size());
            assertTrue(StructureCache.sizes().containsKey(cache.name()));
        }
    }
}
