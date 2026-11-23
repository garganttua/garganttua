package com.garganttua.api.binding.javalin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.context.HttpCachePolicy;

@DisplayName("HTTP cache — validators and directives")
class JavalinHttpCacheTest {

    @Test
    @DisplayName("the ETag follows the body: the same body, the same tag; another body, another tag")
    void etagFollowsBody() {
        String one = JavalinHttpCache.etag("[{\"name\":\"a\"}]".getBytes(StandardCharsets.UTF_8));

        assertEquals(one, JavalinHttpCache.etag("[{\"name\":\"a\"}]".getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(one, JavalinHttpCache.etag("[{\"name\":\"b\"}]".getBytes(StandardCharsets.UTF_8)));
        assertTrue(one.startsWith("\"") && one.endsWith("\""), "a quoted, strong validator");
    }

    @Test
    @DisplayName("If-None-Match matches its tag, weak or not, in a list, or *")
    void ifNoneMatch() {
        assertTrue(JavalinHttpCache.matches("\"abc\"", "\"abc\""));
        assertTrue(JavalinHttpCache.matches("W/\"abc\"", "\"abc\""));
        assertTrue(JavalinHttpCache.matches("\"x\", \"abc\"", "\"abc\""));
        assertTrue(JavalinHttpCache.matches("*", "\"abc\""));
        assertFalse(JavalinHttpCache.matches("\"other\"", "\"abc\""));
        assertFalse(JavalinHttpCache.matches(null, "\"abc\""));
    }

    @Test
    @DisplayName("public applies to anonymous requests only; an authenticated one is always private")
    void directives() {
        HttpCachePolicy policy = HttpCachePolicy.publicFor(Duration.ofMinutes(5)).staleWhileRevalidate(Duration.ofMinutes(1));

        assertEquals("public, max-age=300, stale-while-revalidate=60", policy.cacheControl(false));
        assertEquals("private, max-age=300, stale-while-revalidate=60", policy.cacheControl(true));
        assertEquals("private, max-age=30", HttpCachePolicy.privateFor(Duration.ofSeconds(30)).cacheControl(false));
        assertEquals("private, no-cache", HttpCachePolicy.revalidated().cacheControl(false));
    }
}
