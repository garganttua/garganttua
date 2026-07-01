package com.garganttua.events.api.context;

/**
 * Route synchronization configuration: how a route acquires a mutex to serialize per-message
 * processing. The lock source is resolved at runtime in this order:
 * <ul>
 *   <li>{@code lockBean} (non-blank) — the per-message workflow runs under the
 *       {@link com.garganttua.core.mutex.IMutex} bean resolved by this reference/name from the
 *       injection context. Set by the {@code synchronization(IMutex)} /
 *       {@code synchronization(ISupplierBuilder)} DSL forms (which register the provided mutex as a
 *       bean and store its generated name here) and by {@code synchronizationBean(String)} (which
 *       stores the caller's bean reference directly).</li>
 *   <li>otherwise {@code lock} — a mutex name resolved through the core {@code IMutexManager}: a
 *       plain name uses the default {@code InterruptibleLeaseMutex}; a qualified {@code Type::name}
 *       selects a registered {@code @MutexFactory} (so distributed locking plugs in via the core
 *       mutex SPI). A non-blank {@code lockObject} narrows the name (e.g. per tenant/entity).</li>
 * </ul>
 * {@code lockObject} applies only to the name-based {@code lock} path; a directly-provided mutex
 * (bean / object / supplier) is a single instance and cannot be sub-keyed.
 *
 * @param lock       the mutex name (plain or {@code Type::name}), or {@code null} for the bean path
 * @param lockObject optional key-narrowing suffix for the {@code lock} path, may be {@code null}
 * @param lockBean   the mutex bean reference/name, or {@code null} for the name path
 */
public record RouteSyncDef(
		String lock,
		String lockObject,
		String lockBean) {

	/**
	 * Back-compatible name-based synchronization ({@code lockBean} is {@code null}). Preserves every
	 * existing 2-arg call site and JSON config, which never carried a bean reference.
	 *
	 * @param lock       the mutex name (plain or {@code Type::name})
	 * @param lockObject optional key-narrowing suffix, may be {@code null}
	 */
	public RouteSyncDef(String lock, String lockObject) {
		this(lock, lockObject, null);
	}
}
