package com.garganttua.core.cache;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.annotations.Reflected;

/**
 * Resolves caches by name through the factory of their implementation, and keeps them: the same name is the same
 * cache. An implementation without a factory is refused rather than replaced by an in-memory cache — on a cluster,
 * a silent local cache would serve values other nodes have invalidated.
 */
@Reflected
public final class CacheManager implements ICacheManager {

    private static final Logger log = Logger.getLogger(CacheManager.class);

    private final Map<IClass<?>, ICacheFactory> factories;
    private final Map<String, ICache<?, ?>> caches = new ConcurrentHashMap<>();

    public CacheManager(Map<IClass<?>, ICacheFactory> factories) {
        this.factories = Map.copyOf(Objects.requireNonNull(factories, "Factories cannot be null"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> ICache<K, V> cache(CacheName name, CacheSpec spec) throws CacheException {
        Objects.requireNonNull(name, "Cache name cannot be null");
        Objects.requireNonNull(spec, "Cache spec cannot be null");
        ICacheFactory factory = this.factories.get(name.type());
        if (factory == null) {
            throw new CacheException("No factory for the cache implementation " + name.type().getSimpleName()
                    + " (cache '" + name.name() + "'): register it with withFactory(...) or a scanned @CacheFactory");
        }
        return (ICache<K, V>) this.caches.computeIfAbsent(name.toString(), key -> {
            log.debug("Creating cache {}", key);
            return factory.createCache(name.name(), spec);
        });
    }
}
