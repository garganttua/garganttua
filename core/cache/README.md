# Garganttua Cache

## Description

The Garganttua Cache module puts every cache of the framework and of its applications behind one generic contract, `ICache`, defined in `garganttua-commons` — the cache counterpart of `IMutex`. Code reads through the interface; the implementation is chosen by name and can change by configuration, from a single node to a cluster, without touching the code. This module ships the in-memory implementations and the manager that resolves a cache by name; `garganttua-cache-redis` adds the distributed ones.

## Installation

<!-- AUTO-GENERATED-START -->
### Installation with Maven
```xml
<dependency>
    <groupId>com.garganttua.core</groupId>
    <artifactId>garganttua-cache</artifactId>
    <version>3.0.0-ALPHA37</version>
</dependency>
```

### Actual version
3.0.0-ALPHA37

### Dependencies
 - `com.garganttua.core:garganttua-aot-reflection`
 - `com.garganttua.core:garganttua-aot-commons`
 - `com.garganttua.core:garganttua-commons`
 - `com.garganttua.core:garganttua-dsl`
 - `com.garganttua.core:garganttua-reflection:test`
 - `com.garganttua.core:garganttua-runtime-reflection:test`
 - `com.garganttua.core:garganttua-reflections:test`

<!-- AUTO-GENERATED-END -->

## Core Concepts

| Contract (`garganttua-commons`) | Role |
|---|---|
| `ICache<K, V>` | `get`, `get(key, loader)`, `put`, `invalidate`, `invalidateAll`, `stats()` |
| `CacheSpec` | how many entries per node, how long each lives, and — for a store outside the process — the codec of its values |
| `CacheName` | `Type::name`: the implementation and the cache's identifier |
| `ICacheFactory`, `@CacheFactory(type = …)` | creates the caches of one implementation; annotated, it is found by package scanning, natively too |
| `ICacheManager` | resolves a cache by name — the same name is the same cache |

| Implementation | Module | Use |
|---|---|---|
| `LocalCache` | this one | in memory, bounded, expiring — the default, single node |
| `NoCache` | this one | keeps nothing — turns a declared cache off |
| `RedisCache` | `garganttua-cache-redis` | values shared in redis, encoded by the spec's codec |
| `RedisNearCache` | `garganttua-cache-redis` | a local copy per node, invalidations broadcast over redis |

### Key Features

- **Never wrong, at worst slower.** An implementation that cannot vouch for its entries — a store that does not answer, a broadcast it no longer listens to — behaves as an empty cache: reads go to the source.
- **The time to live bounds a lost invalidation.** Whatever happens, no entry outlives `CacheSpec.ttl()`.
- **A load overtaken by an invalidation is not cached.** `get(key, loader)` caches what it loaded only when no invalidation of the cache happened meanwhile — otherwise it would serve what a concurrent write had just replaced.
- **An unknown implementation is refused**, not silently replaced by a local cache that would serve, on a cluster, values other nodes had invalidated.
- **Keys are their string form** — the only form a distributed implementation can carry.

## Usage

```java
ICacheManager caches = CacheManagerBuilder.builder()
        .withFactory(IClass.getClass(RedisNearCache.class), new RedisNearCacheFactory(jedisPool))
        .build();

ICache<String, Rate> rates = caches.cache(
        new CacheName(IClass.getClass(LocalCache.class), "rates"),
        CacheSpec.ttl(30, TimeUnit.SECONDS).maxEntries(5_000));

Rate rate = rates.get(code, () -> loadRate(code));   // loaded on a miss, then cached
rates.invalidate(code);                               // after the source changed
```

With auto-detection on, the `@CacheFactory` classes of `withPackage(...)` are registered without `withFactory`.

In garganttua-api, a domain caches its reads by uuid with `domain.cache(caches, IClass.getClass(RedisNearCache.class), CacheSpec.of())` — see the api README.

## Tips and best practices

- **Pick the implementation by deployment, not by code**: `LocalCache` on one node, `RedisNearCache` on a cluster when reads dominate, `RedisCache` when every node must see one value, `NoCache` to measure without.
- **Keep the time to live short** for data that a write outside the framework can change: it is the only bound on such a change.
- **Read `stats()`** — a low `hitRate()` means the key does not repeat: cache elsewhere, or make the key repeatable.

## Parameters

<!-- AUTO-GENERATED-PARAMETERS-START -->
Parameters this module declares or reads. Pass them with `-D` — on the JVM running the application for runtime scope, on the Maven command line for build scope.

| Parameter | Scope | Values | Default | Effect |
|---|---|---|---|---|
| `-Dgarganttua.direct.binders=true` | Build (Maven / APT) | `true` \| `false` | `false` (set in `core/pom.xml`; most function modules override it to `true`) | Maven property forwarded to javac as `-Agarganttua.direct.binders` and consumed by `garganttua-aot-annotation-processor`. When on, the module ships compile-time `AOTClass_*` descriptors for its `@Reflected` classes — required for a native build to see them. Modules inside the `aot-commons`/`aot-reflection` dependency cycle must stay `false`. |
<!-- AUTO-GENERATED-PARAMETERS-END -->

## License

This module is distributed under the MIT License.
