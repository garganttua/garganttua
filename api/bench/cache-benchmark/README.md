# garganttua-api-cache-benchmark

Every cache level of garganttua-api, with and without, against a real MongoDB, PostgreSQL and redis. Not a library,
never published, built only with `-Pbench`.

```bash
docker run -d --rm --name gg-bench-mongo -p 127.0.0.1:27099:27017 mongo:7
docker run -d --rm --name gg-bench-pg -p 127.0.0.1:54399:5432 -e POSTGRES_PASSWORD=bench -e POSTGRES_DB=bench postgis/postgis:17-3.5
docker run -d --rm --name gg-bench-redis -p 127.0.0.1:6392:6379 redis:7-alpine
mvn -Pbench test -pl :garganttua-api-cache-benchmark -Dbench=true -Dgarganttua.log.level=WARN
```

`-Dbench.mongo`, `-Dbench.postgres` (with `.user` / `.password`) and `-Dbench.redis` point elsewhere. The report is
written to `target/cache-benchmark.md`. About 4 minutes.

What it measures, in `CacheBenchmarkTest`:

- **Reads by uuid (L2/L3)** — the reads token verification makes on every authenticated request; 80 % of them on
  10 % of the rows; with and without 2 % writes (the writes are inside the timed loop).
- **Searches (L4)** — the same 20 searches over and over, as a dashboard does.
- **Map (tiles)** — a session of 300 viewports, panned and zoomed, never twice the same. Each variant is checked
  against the store, viewport by viewport.
- **HTTP (L5)** — a 200-row list over Javalin: the body every time, a `304`, a `304` over the query cache.

Single-threaded client, everything on the loopback, one run: read the orders of magnitude, not the last digit.
What a cache saves is store round trips — on a real network the gaps widen.

## Last results

2026-11-23, 14 cores, JDK 25.0.1, stores in Docker on the loopback.

Columns: operations per second, mean / p50 / p99 latency in µs, queries that reached the store.

### MONGODB

#### Reads by uuid (L2/L3) — 5000 entities, 50000 reads, 80 % on 500 of them, no writes

| variant | ops/s | mean µs | p50 µs | p99 µs | store queries |
|---|---|---|---|---|---|
| no cache | 7,023 | 141.8 | 109.7 | 474.1 | 50,000 |
| LocalCache | 115,697 | 8.6 | 1.2 | 134.0 | 3,564 |
| RedisNearCache | 104,361 | 9.5 | 1.4 | 142.3 | 3,564 |
| RedisCache | 14,095 | 70.8 | 49.2 | 456.0 | 3,564 |

#### Reads by uuid (L2/L3) — 5000 entities, 50000 reads, 80 % on 500 of them, 2 % writes

| variant | ops/s | mean µs | p50 µs | p99 µs | store queries |
|---|---|---|---|---|---|
| no cache | 8,721 | 114.5 | 102.6 | 327.0 | 50,000 |
| LocalCache | 62,557 | 15.9 | 1.5 | 165.9 | 4,482 |
| RedisNearCache | 52,524 | 18.9 | 2.5 | 242.5 | 4,807 |
| RedisCache | 12,539 | 79.6 | 52.6 | 435.7 | 4,482 |

#### Searches (L4) — 20 distinct (tenant × kind), 5000 reads, no writes

| variant | ops/s | mean µs | p50 µs | p99 µs | store queries |
|---|---|---|---|---|---|
| no cache | 495 | 2017.1 | 1931.5 | 3892.1 | 5,000 |
| query cache, LocalCache | 3,147 | 317.5 | 280.2 | 809.1 | 0 |
| query cache, RedisNearCache | 3,284 | 304.2 | 274.6 | 586.0 | 0 |

#### Searches (L4) — 20 distinct (tenant × kind), 5000 reads, 2 % writes

| variant | ops/s | mean µs | p50 µs | p99 µs | store queries |
|---|---|---|---|---|---|
| no cache | 598 | 1670.4 | 1533.0 | 3138.4 | 5,100 |
| query cache, LocalCache | 1,229 | 813.2 | 333.6 | 2568.9 | 1,936 |
| query cache, RedisNearCache | 1,054 | 948.0 | 457.1 | 3398.0 | 2,027 |

#### Map (tiles) — 30000 places, 300 viewports panned and zoomed (0.05° to 1.5°), one tenant

| variant | ops/s | mean µs | p50 µs | p99 µs | store queries |
|---|---|---|---|---|---|
| no cache | 2,278 | 437.8 | 250.0 | 2927.0 | 301 |
| query cache (exact key), LocalCache | 2,724 | 353.3 | 288.7 | 1004.1 | 288 |
| tiles, LocalCache | 3,869 | 257.0 | 35.8 | 2476.9 | 115 |
| tiles, RedisNearCache | 4,474 | 222.8 | 19.7 | 2317.6 | 115 |

Every cached variant returned, for every viewport, the rows the store returned.

### POSTGRESQL

#### Reads by uuid (L2/L3) — 5000 entities, 50000 reads, 80 % on 500 of them, no writes

| variant | ops/s | mean µs | p50 µs | p99 µs | store queries |
|---|---|---|---|---|---|
| no cache | 18,986 | 52.5 | 45.7 | 132.6 | 50,000 |
| LocalCache | 215,322 | 4.6 | 1.1 | 55.3 | 3,564 |
| RedisNearCache | 151,581 | 6.5 | 1.9 | 78.0 | 3,564 |
| RedisCache | 14,765 | 67.6 | 52.4 | 323.3 | 3,564 |

#### Reads by uuid (L2/L3) — 5000 entities, 50000 reads, 80 % on 500 of them, 2 % writes

| variant | ops/s | mean µs | p50 µs | p99 µs | store queries |
|---|---|---|---|---|---|
| no cache | 11,629 | 85.9 | 51.5 | 1206.7 | 50,000 |
| LocalCache | 34,132 | 29.2 | 2.1 | 1074.6 | 4,482 |
| RedisNearCache | 29,488 | 33.8 | 2.9 | 1181.2 | 4,804 |
| RedisCache | 10,634 | 93.9 | 53.6 | 1295.1 | 4,482 |

#### Searches (L4) — 20 distinct (tenant × kind), 5000 reads, no writes

| variant | ops/s | mean µs | p50 µs | p99 µs | store queries |
|---|---|---|---|---|---|
| no cache | 948 | 1053.4 | 1083.4 | 1862.6 | 5,000 |
| query cache, LocalCache | 3,528 | 283.2 | 270.9 | 420.4 | 0 |
| query cache, RedisNearCache | 3,362 | 297.2 | 272.5 | 503.7 | 0 |

#### Searches (L4) — 20 distinct (tenant × kind), 5000 reads, 2 % writes

| variant | ops/s | mean µs | p50 µs | p99 µs | store queries |
|---|---|---|---|---|---|
| no cache | 1,035 | 965.0 | 936.8 | 2820.7 | 5,100 |
| query cache, LocalCache | 1,824 | 547.8 | 382.2 | 2206.1 | 1,936 |
| query cache, RedisNearCache | 1,507 | 662.8 | 468.1 | 2441.7 | 2,028 |

#### Map (tiles) — 30000 places, 300 viewports panned and zoomed (0.05° to 1.5°), one tenant

| variant | ops/s | mean µs | p50 µs | p99 µs | store queries |
|---|---|---|---|---|---|
| no cache | 3,893 | 255.9 | 189.6 | 616.7 | 301 |
| query cache (exact key), LocalCache | 4,381 | 225.9 | 200.8 | 588.4 | 288 |
| tiles, LocalCache | 9,709 | 102.3 | 18.4 | 971.0 | 115 |
| tiles, RedisNearCache | 13,739 | 72.3 | 13.3 | 580.6 | 115 |

Every cached variant returned, for every viewport, the rows the store returned.

### HTTP (L5) — GET of a 200-row list over Javalin, MongoDB, 1000 requests

| variant | req/s | mean µs | p50 µs | p99 µs | store queries | bytes received |
|---|---|---|---|---|---|---|
| no HTTP cache | 146 | 6853.7 | 6637.8 | 11586.3 | 1,000 | 67,471,000 |
| ETag + If-None-Match (304) | 166 | 6013.5 | 5884.6 | 8381.4 | 1,000 | 0 |
| ETag (304) + query cache | 271 | 3684.1 | 3703.4 | 5259.2 | 0 | 0 |
