package com.garganttua.api.commons.context;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.garganttua.core.mutex.IMutex;
import com.garganttua.core.mutex.IMutexManager;
import com.garganttua.core.mutex.MutexName;
import com.garganttua.core.mutex.MutexStrategy;
import com.garganttua.core.reflection.IClass;

/**
 * How a domain serializes its WRITES across instances.
 *
 * <p>
 * Two nodes serving one database interleave a partial update without a word: each reads the stored
 * entity, merges its own non-null fields, and writes the whole thing back, so the second write
 * erases what the first decided. Counting invariants — a subscription quota that counts rows then
 * authorizes the creation — break the same way, and both nodes answer {@code 200}. Declaring a
 * policy makes the write stages of a domain run under a mutex keyed by tenant and entity, so the
 * read-merge-write is atomic for everyone reading that key.
 * </p>
 *
 * <p>
 * <strong>The platform never builds the manager.</strong> Whoever wants synchronization supplies an
 * {@link IMutexManager} and the {@link IMutex} implementation to use — a distributed one for several
 * nodes, the local lease mutex for one. That keeps {@code garganttua-api} free of any dependency on
 * a lock provider: without a declared policy, not one character is added to the generated pipeline.
 * </p>
 *
 * @param manager   resolves a {@link MutexName} into a mutex; supplied by the caller
 * @param mutexType the mutex implementation to acquire — what decides local versus distributed
 * @param keyPrefix first segment of every key this policy builds; the domain name when left null
 * @param strategy  wait, retries and — mandatory — the lease that frees the key if a node dies
 *                  holding it
 * @param useCases  names of the use cases to synchronize as well; CRUD writes always are
 */
public record SynchronizationPolicy(
        IMutexManager manager,
        IClass<? extends IMutex> mutexType,
        String keyPrefix,
        MutexStrategy strategy,
        Set<String> useCases) {

    /**
     * Waits up to 10 s for the key, then gives up; holds it at most 30 s.
     *
     * <p>
     * The lease is the part that matters: a node that dies inside the critical section must not
     * freeze the others forever, which is why a policy without a lease is refused.
     * </p>
     */
    public static final MutexStrategy DEFAULT_STRATEGY =
            new MutexStrategy(10, TimeUnit.SECONDS, 0, 0, TimeUnit.MILLISECONDS, 30, TimeUnit.SECONDS);

    /** Separates the segments of a key: prefix, tenant, entity. */
    public static final String KEY_SEPARATOR = ":";

    public SynchronizationPolicy {
        Objects.requireNonNull(manager, "A synchronization policy needs a mutex manager — the "
                + "platform does not build one, since it depends on no lock provider.");
        Objects.requireNonNull(mutexType, "A synchronization policy needs a mutex type: it is what "
                + "decides whether the lock is local to this JVM or shared between instances.");
        strategy = strategy == null ? DEFAULT_STRATEGY : strategy;
        if (strategy.leaseTime() <= 0) {
            throw new IllegalArgumentException("A synchronization policy requires a lease: without "
                    + "one, a node that dies holding a key blocks every other node on it forever.");
        }
        useCases = useCases == null ? Set.of() : Set.copyOf(useCases);
    }

    /** A policy on {@code mutexType}, keyed by the domain name, with the default strategy. */
    public static SynchronizationPolicy of(IMutexManager manager, IClass<? extends IMutex> mutexType) {
        return new SynchronizationPolicy(manager, mutexType, null, DEFAULT_STRATEGY, Set.of());
    }

    /**
     * A policy read off a qualified mutex name ({@code com.acme.RedisMutex::orders}) — the form
     * {@code garganttua-events} uses in {@code RouteSyncDef}, so one declaration reads the same
     * across the platform.
     *
     * @param manager       resolves the name into a mutex
     * @param qualifiedName {@code Type::name}; the type is loaded by name, the name becomes the key
     *                      prefix
     * @return the policy
     */
    public static SynchronizationPolicy of(IMutexManager manager, String qualifiedName) {
        MutexName parsed = MutexName.fromString(qualifiedName);
        return new SynchronizationPolicy(manager, parsed.type(), parsed.name(), DEFAULT_STRATEGY, Set.of());
    }

    /** This policy with {@code strategy} instead of the current one. */
    public SynchronizationPolicy withStrategy(MutexStrategy other) {
        return new SynchronizationPolicy(manager, mutexType, keyPrefix, other, useCases);
    }

    /** This policy also covering the named use cases. */
    public SynchronizationPolicy withUseCases(Set<String> names) {
        return new SynchronizationPolicy(manager, mutexType, keyPrefix, strategy, names);
    }

    /** Whether the named use case runs under the lock. CRUD writes always do. */
    public boolean covers(String useCaseName) {
        return useCaseName != null && useCases.contains(useCaseName);
    }

    /**
     * The mutex name for one write.
     *
     * <p>
     * Segments narrow the key: {@code <prefix>:<tenant>:<entity>}. The entity is absent on a
     * creation — there is no uuid yet — which is exactly the granularity a counting invariant needs:
     * the creations of ONE tenant serialize, those of other tenants do not.
     * </p>
     *
     * @param domainName the domain, used as prefix when the policy declares none
     * @param tenantId   the caller's tenant, or null outside multi-tenancy
     * @param entityUuid the entity being written, or null on a creation
     * @return the name to acquire
     */
    public MutexName keyFor(String domainName, String tenantId, String entityUuid) {
        StringBuilder key = new StringBuilder(
                keyPrefix == null || keyPrefix.isBlank() ? domainName : keyPrefix);
        if (tenantId != null && !tenantId.isBlank()) {
            key.append(KEY_SEPARATOR).append(tenantId);
        }
        if (entityUuid != null && !entityUuid.isBlank()) {
            key.append(KEY_SEPARATOR).append(entityUuid);
        }
        return new MutexName(mutexType, key.toString());
    }
}
