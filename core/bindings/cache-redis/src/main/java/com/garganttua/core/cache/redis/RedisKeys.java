package com.garganttua.core.cache.redis;

import java.nio.charset.StandardCharsets;

/** Where a cache's entries and invalidations live in redis: {@code garganttua:cache:<name>:…}. */
final class RedisKeys {

    /** The prefix of every key and channel this binding uses. */
    static final String PREFIX = "garganttua:cache:";

    private RedisKeys() {
    }

    static String entryPrefix(String cacheName) {
        return PREFIX + cacheName + ":e:";
    }

    static byte[] entry(String cacheName, Object key) {
        return (entryPrefix(cacheName) + key).getBytes(StandardCharsets.UTF_8);
    }

    static String channel(String cacheName) {
        return PREFIX + cacheName + ":invalidations";
    }
}
