package com.garganttua.core.cache.redis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.garganttua.core.cache.CacheException;
import com.garganttua.core.cache.CacheSpec;
import com.garganttua.core.cache.CacheStats;
import com.garganttua.core.cache.ICache;
import com.garganttua.core.cache.ICacheCodec;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.annotations.Reflected;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;

/**
 * A cache whose values live in redis: every node reads the same value, written once. Values cross the network,
 * so the spec carries the {@link CacheSpec#codec() codec} that encodes them; each entry expires in redis after the
 * spec's time to live. The bound on the number of entries is redis's to enforce ({@code maxmemory} policy).
 *
 * <p>
 * <b>Redis unreachable: the cache is absent.</b> A read misses and goes to the source, a write is dropped. A
 * failed invalidation is logged as an error: the stale entry lives at most the time to live.
 * </p>
 */
@Reflected
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class RedisCache<K, V> implements ICache<K, V> {

    private static final Logger log = Logger.getLogger(RedisCache.class);
    private static final int SCAN_BATCH = 500;

    private final String name;
    private final JedisPool pool;
    private final ICacheCodec<V> codec;
    private final long ttlMillis;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong invalidations = new AtomicLong();

    @SuppressWarnings("unchecked")
    public RedisCache(String name, CacheSpec spec, JedisPool pool) throws CacheException {
        this.name = Objects.requireNonNull(name, "Cache name cannot be null");
        this.pool = Objects.requireNonNull(pool, "Jedis pool cannot be null");
        this.codec = (ICacheCodec<V>) spec.codec().orElseThrow(() -> new CacheException("RedisCache '" + name
                + "' stores values in redis: its CacheSpec needs a codec — CacheSpec.of().codec(...)"));
        this.ttlMillis = Math.max(1, spec.ttl().toMillis());
    }

    @Override
    public String name() {
        return this.name;
    }

    @Override
    public Optional<V> get(K key) {
        Optional<byte[]> bytes = read(key);
        if (bytes.isEmpty()) {
            this.misses.incrementAndGet();
            return Optional.empty();
        }
        this.hits.incrementAndGet();
        return Optional.of(this.codec.decode(bytes.get()));
    }

    /** {@return the stored bytes of {@code key}, empty when absent or when redis does not answer} */
    private Optional<byte[]> read(K key) {
        try (Jedis jedis = this.pool.getResource()) {
            return Optional.ofNullable(jedis.get(RedisKeys.entry(this.name, key)));
        } catch (RuntimeException e) {
            log.warn("Cache '{}': redis unreachable, reading from the source ({})", this.name, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public V get(K key, Supplier<? extends V> loader) {
        Optional<V> cached = get(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        V loaded = loader.get();
        if (loaded != null) {
            put(key, loaded);
        }
        return loaded;
    }

    @Override
    public void put(K key, V value) {
        if (value == null) {
            invalidate(key);
            return;
        }
        try (Jedis jedis = this.pool.getResource()) {
            jedis.psetex(RedisKeys.entry(this.name, key), this.ttlMillis, this.codec.encode(value));
        } catch (RuntimeException e) {
            log.warn("Cache '{}': redis unreachable, value not cached ({})", this.name, e.getMessage());
        }
    }

    @Override
    public void invalidate(K key) {
        this.invalidations.incrementAndGet();
        try (Jedis jedis = this.pool.getResource()) {
            jedis.del(RedisKeys.entry(this.name, key));
        } catch (RuntimeException e) {
            log.error("Cache '{}': key {} could not be invalidated in redis — a stale value may be served for at most "
                    + "{} ms ({})", this.name, key, this.ttlMillis, e.getMessage());
        }
    }

    @Override
    public void invalidateAll() {
        this.invalidations.incrementAndGet();
        byte[] pattern = (RedisKeys.entryPrefix(this.name) + "*").getBytes(StandardCharsets.UTF_8);
        try (Jedis jedis = this.pool.getResource()) {
            byte[] cursor = ScanParams.SCAN_POINTER_START_BINARY;
            ScanParams params = new ScanParams().match(pattern).count(SCAN_BATCH);
            do {
                ScanResult<byte[]> page = jedis.scan(cursor, params);
                List<byte[]> keys = new ArrayList<>(page.getResult());
                if (!keys.isEmpty()) {
                    jedis.del(keys.toArray(new byte[0][]));
                }
                cursor = page.getCursorAsBytes();
            } while (!java.util.Arrays.equals(cursor, ScanParams.SCAN_POINTER_START_BINARY));
        } catch (RuntimeException e) {
            log.error("Cache '{}': could not be emptied in redis — stale values may be served for at most {} ms ({})",
                    this.name, this.ttlMillis, e.getMessage());
        }
    }

    @Override
    public CacheStats stats() {
        return new CacheStats(this.hits.get(), this.misses.get(), this.invalidations.get(), -1);
    }
}
