package com.garganttua.core.script;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.core.script.context.ScriptSourceResolver;

/**
 * Classpath script sources are read once per class loader.
 *
 * <p>
 * {@code include("classpath:…")} runs on per-request paths — the api's pipeline includes its stage
 * scripts on every request. The compilation of an included script was cached, but the read that
 * feeds the cache was not, so every request re-opened each script on disk; under concurrent load
 * the request threads were sampled in {@code FileInputStream.open}.
 * </p>
 */
@DisplayName("ScriptSourceResolver.readClasspathResource")
class ScriptSourceResolverTest {

    private static final String RESOURCE = "scripts/classpath-hello.gs";

    @Test
    @DisplayName("a second read of the same resource is served from memory")
    void secondReadIsCached() throws Exception {
        String first = ScriptSourceResolver.readClasspathResource(RESOURCE).orElseThrow();
        String second = ScriptSourceResolver.readClasspathResource(RESOURCE).orElseThrow();

        assertSame(first, second, "the source was read again instead of being reused");
    }

    @Test
    @DisplayName("another context class loader reads its own copy")
    void cacheIsPerClassLoader() throws Exception {
        String fromTestLoader = ScriptSourceResolver.readClasspathResource(RESOURCE).orElseThrow();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader other = new URLClassLoader(new URL[0], previous)) {
            thread.setContextClassLoader(other);
            String fromOtherLoader = ScriptSourceResolver.readClasspathResource(RESOURCE).orElseThrow();
            assertNotSame(fromTestLoader, fromOtherLoader,
                    "a different application's loader must not be served another loader's script");
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    @DisplayName("a missing resource stays missing")
    void missingResourceIsEmpty() throws Exception {
        assertTrue(ScriptSourceResolver.readClasspathResource("scripts/does-not-exist.gs").isEmpty());
    }
}
