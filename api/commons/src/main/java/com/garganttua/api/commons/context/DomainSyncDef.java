package com.garganttua.api.commons.context;

/**
 * Per-domain write-synchronization configuration: serializes a domain's write operations
 * (create / update / delete) through a garganttua-core mutex, mirroring the events
 * {@code route(...).synchronization(...)} model. The lock source is resolved at runtime in this order:
 * <ul>
 *   <li>{@code lockBean} (non-blank) — write operations run under the
 *       {@link com.garganttua.core.mutex.IMutex} bean resolved by this reference/name from the
 *       injection context. Set by the {@code synchronization(IMutex)} /
 *       {@code synchronization(ISupplierBuilder)} DSL forms (which register the provided mutex as a
 *       bean and store its generated name here) and by {@code synchronizationBean(String)} (which
 *       stores the caller's bean reference directly).</li>
 *   <li>otherwise {@code lock} — a mutex name resolved through the core {@code IMutexManager}: a plain
 *       name uses the default in-JVM {@code InterruptibleLeaseMutex}; a qualified {@code Type::name}
 *       selects a registered {@code @MutexFactory} type (so a distributed lock — e.g.
 *       {@code RedisMutex::orders} — plugs in via the core mutex SPI, with no api change). A non-blank
 *       {@code lockObject} is a static suffix that narrows the name ({@code lock:lockObject}).</li>
 * </ul>
 * This is coarse per-domain locking, not per-entity dynamic keying. {@code lockObject} applies only to
 * the name-based {@code lock} path; a directly-provided mutex (bean / object / supplier) is a single
 * instance and cannot be sub-keyed. Read operations are never synchronized.
 *
 * @param lock       the mutex name (plain or {@code Type::name}), or {@code null} for the bean path
 * @param lockObject an optional static suffix narrowing the {@code lock} name, or {@code null}
 * @param lockBean   the mutex bean reference/name, or {@code null} for the name path
 */
public record DomainSyncDef(String lock, String lockObject, String lockBean) {

    /**
     * Back-compatible name-based synchronization ({@code lockBean} is {@code null}). Preserves the
     * existing 2-arg call sites, which never carried a bean reference.
     *
     * @param lock       the mutex name (plain or {@code Type::name})
     * @param lockObject an optional static suffix narrowing the name, may be {@code null}
     */
    public DomainSyncDef(String lock, String lockObject) {
        this(lock, lockObject, null);
    }
}
