package com.garganttua.api.commons.context;

/**
 * Per-domain write-synchronization configuration: serializes a domain's write operations
 * (create / update / delete) through a garganttua-core mutex, mirroring the events
 * {@code route(...).synchronization(lock, lockObject)} model.
 *
 * <p>{@code lock} names the mutex. A plain name resolves to the default in-JVM
 * {@code InterruptibleLeaseMutex}; a qualified {@code Type::name} selects a registered
 * {@code @MutexFactory} type (so a distributed lock — e.g. {@code RedisMutex::orders} — plugs in via
 * the core mutex SPI, with no api change). {@code lockObject} is an optional static suffix that
 * narrows the lock name ({@code lock:lockObject}); it is coarse per-domain locking, not per-entity
 * dynamic keying. Read operations are never synchronized.</p>
 *
 * @param lock       the mutex name, plain or {@code Type::name}-qualified (required to synchronize)
 * @param lockObject an optional static suffix narrowing the lock name, or {@code null}
 */
public record DomainSyncDef(String lock, String lockObject) {
}
