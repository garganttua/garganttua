package com.garganttua.core.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.core.cache.dsl.CacheManagerBuilder;
import com.garganttua.core.cache.fixtures.CountingCache;
import com.garganttua.core.cache.fixtures.CountingCacheFactory;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;
import com.garganttua.core.reflections.ReflectionsAnnotationScanner;

@DisplayName("CacheManager — a cache per name, through the factory of its implementation")
class CacheManagerTest {

    @BeforeAll
    static void reflection() {
        IClass.setReflection(ReflectionBuilder.builder()
                .withProvider(new RuntimeReflectionProvider())
                .withScanner(new ReflectionsAnnotationScanner())
                .build());
    }

    private static CacheName name(Class<?> type, String name) {
        return new CacheName(IClass.getClass(type), name);
    }

    @Test
    @DisplayName("the in-memory implementations are always served, and a name is always the same cache")
    void defaultsAndIdentity() {
        ICacheManager caches = CacheManagerBuilder.builder().build();

        ICache<String, String> users = caches.cache(name(LocalCache.class, "users"), CacheSpec.of());
        users.put("u1", "Ana");

        assertSame(users, caches.cache(name(LocalCache.class, "users"), CacheSpec.of()));
        assertEquals(Optional.of("Ana"), caches.<String, String>cache(name(LocalCache.class, "users"), CacheSpec.of())
                .get("u1"));
        assertNotSame(users, caches.cache(name(LocalCache.class, "other"), CacheSpec.of()));
        assertInstanceOf(NoCache.class, caches.cache(name(NoCache.class, "off"), CacheSpec.of()));
    }

    @Test
    @DisplayName("an implementation without a factory is refused, not replaced by a local cache")
    void unknownImplementationRefused() {
        ICacheManager caches = CacheManagerBuilder.builder().build();

        CacheException refused = assertThrows(CacheException.class,
                () -> caches.cache(name(CountingCache.class, "x"), CacheSpec.of()));
        assertTrue(refused.getMessage().contains("No factory for the cache implementation CountingCache"),
                refused.getMessage());
    }

    @Test
    @DisplayName("a registered factory serves its implementation")
    void registeredFactory() {
        ICacheManager caches = CacheManagerBuilder.builder()
                .withFactory(IClass.getClass(CountingCache.class), new CountingCacheFactory())
                .build();

        assertInstanceOf(CountingCache.class, caches.cache(name(CountingCache.class, "x"), CacheSpec.of()));
    }

    @Test
    @DisplayName("auto-detection registers the @CacheFactory classes of the scanned packages")
    void scannedFactory() {
        ICacheManager caches = CacheManagerBuilder.builder()
                .withPackage("com.garganttua.core.cache.fixtures")
                .autoDetect(true)
                .build();

        assertInstanceOf(CountingCache.class, caches.cache(name(CountingCache.class, "scanned"), CacheSpec.of()));
    }

    @Test
    @DisplayName("a name must designate an ICache implementation")
    void nameTypeChecked() {
        assertThrows(IllegalArgumentException.class, () -> name(String.class, "x"));
        assertEquals("LocalCache::users", name(LocalCache.class, "users").toString());
    }
}
