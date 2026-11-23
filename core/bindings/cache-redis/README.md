# Garganttua Cache Redis

## Description

The distributed implementations of `ICache` (see `garganttua-cache`) over redis, on the same Jedis client as `garganttua-mutex-redis`. `RedisCache` keeps one value for every node in redis; `RedisNearCache` keeps a copy in each node's memory and keeps them coherent by broadcasting invalidations.

## Installation

<!-- AUTO-GENERATED-START -->
### Installation with Maven
```xml
<dependency>
    <groupId>com.garganttua.core</groupId>
    <artifactId>garganttua-cache-redis</artifactId>
    <version>3.0.0-ALPHA37</version>
</dependency>
```

### Actual version
3.0.0-ALPHA37

### Dependencies
 - `redis.clients:jedis`
 - `com.garganttua.core:garganttua-cache`
 - `org.junit.jupiter:junit-jupiter-engine:test`

<!-- AUTO-GENERATED-END -->

## Core Concepts

| | `RedisCache` | `RedisNearCache` |
|---|---|---|
| Where values live | in redis, one value for all nodes | in each node's memory (`LocalCache`) |
| A read that hits | one round trip to redis | no round trip |
| Values cross the network | yes — the spec needs a `codec` | never — no codec |
| Invalidation | `DEL` in redis | removed locally, then `PUBLISH`ed to every node |
| Bound on entries | redis's `maxmemory` policy | `CacheSpec.maxEntries()` per node |
| Redis unreachable | absent: reads go to the source, writes are dropped | not subscribed: the copy is not used, reads go to the source |

### Key Features

- **A node that is not subscribed does not use its copy** (`RedisNearCache`): it cannot know what it missed. When its subscription comes back, it empties its copy first — the invalidations sent meanwhile are lost — then uses it again. The subscription reconnects on its own, pausing 100 ms to 5 s.
- **One subscription per factory, not per cache.** A subscription holds its connection for as long as it lives;
  the caches of one `RedisNearCacheFactory` share a single one (a pattern on every cache channel), so the pool
  needs one connection more than the calls it serves, whatever the number of caches. Create the factory once per
  application, and `close()` it at shutdown.
- **A failed invalidation is logged as an error**: other nodes may serve the old value for at most the time to live.
- **Keys and channels** live under `garganttua:cache:<name>:`.

## Usage

```java
JedisPool pool = new JedisPool("redis", 6379);
ICacheManager caches = CacheManagerBuilder.builder()
        .withFactory(IClass.getClass(RedisNearCache.class), new RedisNearCacheFactory(pool))
        .withFactory(IClass.getClass(RedisCache.class), new RedisCacheFactory(pool))
        .build();

// RedisCache stores values in redis: it needs their codec
ICache<String, byte[]> thumbnails = caches.cache(
        new CacheName(IClass.getClass(RedisCache.class), "thumbnails"),
        CacheSpec.of().codec(bytesCodec));
```

The factories' no-argument constructors connect to `localhost:6379`, for auto-detection by package scanning.

## Tips and best practices

- **Prefer `RedisNearCache` for entities** (a domain's reads by uuid): no codec, no round trip on a hit, and invalidations reach every node before the write answers.
- **Use `RedisCache` for large values every node needs once**, where one copy in redis beats one per node.
- **Tests**: `RedisCachesTest` runs against a real redis with `-Dgarganttua.test.redis=host:port` (for instance a throwaway `docker run -p 6391:6379 redis:7-alpine`); without it, only the "redis unreachable" behaviour is tested.

## Parameters

<!-- AUTO-GENERATED-PARAMETERS-START -->
<!-- AUTO-GENERATED-PARAMETERS-END -->

## License

This module is distributed under the MIT License.
