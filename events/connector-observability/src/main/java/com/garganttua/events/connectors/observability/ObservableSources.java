package com.garganttua.events.connectors.observability;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.garganttua.core.observability.IObservable;

/**
 * Application-populated registry mapping a connector-configuration {@code source} key to the
 * live {@link IObservable} the connector should observe.
 *
 * <p>{@link com.garganttua.events.api.IConnector} has no access to the dependency-injection
 * context, so the observable to subscribe to cannot be resolved through DI. Instead the hosting
 * application registers each observable under a stable name with {@link #register(String, IObservable)}
 * before the events engine starts, and the connector resolves it at consumer-start time with
 * {@link #lookup(String)}.</p>
 *
 * <p>The backing map is a {@code private static final} {@link ConcurrentHashMap}; it is never
 * exposed (no getter returning the map), so there is no leaked mutable static state.</p>
 */
public final class ObservableSources {

	private static final ConcurrentMap<String, IObservable> SOURCES = new ConcurrentHashMap<>();

	private ObservableSources() {
		// utility holder — no instances
	}

	/**
	 * Register an observable under a stable name. A later registration with the same name
	 * replaces the previous one.
	 *
	 * @param name       the registry key referenced by a connector's {@code source} configuration
	 * @param observable the live observable to expose; must not be {@code null}
	 */
	public static void register(String name, IObservable observable) {
		Objects.requireNonNull(name, "source name cannot be null");
		Objects.requireNonNull(observable, "observable cannot be null");
		SOURCES.put(name, observable);
	}

	/**
	 * Look up a previously registered observable.
	 *
	 * @param name the registry key
	 * @return the registered observable, or {@link Optional#empty()} if none is registered
	 */
	public static Optional<IObservable> lookup(String name) {
		if (name == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(SOURCES.get(name));
	}

	/**
	 * Remove a previously registered observable.
	 *
	 * @param name the registry key to drop
	 */
	public static void unregister(String name) {
		if (name != null) {
			SOURCES.remove(name);
		}
	}
}
