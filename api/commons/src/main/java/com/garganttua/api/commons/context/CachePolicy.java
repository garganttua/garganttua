package com.garganttua.api.commons.context;

import java.util.Objects;

import com.garganttua.core.cache.CacheName;
import com.garganttua.core.cache.CacheSpec;
import com.garganttua.core.cache.ICache;
import com.garganttua.core.cache.ICacheManager;
import com.garganttua.core.reflection.IClass;

/**
 * How a domain caches its reads by uuid: which manager, which implementation, how entries are held.
 *
 * <p>
 * The cache sits in the repository, under the access control: it holds what the store returned for a uuid, and
 * every request still checks tenant, owner and rights on what it reads. Every write of the repository — the
 * pipeline's and the framework's own (a refresh's rotation, a revocation) — invalidates the uuid before
 * returning, on every node for a distributed implementation. A write made outside the framework is bounded by the
 * spec's time to live.
 * </p>
 *
 * @param manager resolves the cache — one per domain and use, named {@code domain:<name>:<use>}
 * @param type    the implementation: {@code LocalCache}, {@code RedisNearCache}, {@code RedisCache} (which needs a
 *                codec for the domain's DTOs), {@code NoCache}…
 * @param spec    the bound and time to live of its entries
 */
public record CachePolicy(ICacheManager manager, IClass<?> type, CacheSpec spec) {

    public CachePolicy {
        Objects.requireNonNull(manager, "Cache manager cannot be null");
        Objects.requireNonNull(type, "Cache implementation cannot be null");
        Objects.requireNonNull(spec, "Cache spec cannot be null");
    }

    /** {@return the cache of {@code domainName}'s reads by uuid} */
    public <V> ICache<String, V> cacheFor(String domainName) {
        return cacheFor(domainName, "uuid");
    }

    /** {@return the cache named {@code domain:<domainName>:<purpose>} — one per domain and use} */
    public <V> ICache<String, V> cacheFor(String domainName, String purpose) {
        return this.manager.cache(new CacheName(this.type, "domain:" + domainName + ":" + purpose), this.spec);
    }
}
