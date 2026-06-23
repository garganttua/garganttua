package com.garganttua.core.observability;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-global "firehose" of observability events.
 *
 * <p>An observer added here receives <b>every</b> event emitted through
 * {@link ObservabilityEmitter}, <b>exactly once</b>, regardless of which observable produced it.
 * Each engine (Workflow, Runtime, Mapper, ScriptContext, InjectionContext, Bootstrap, api
 * {@code Domain}, …) emits through that single choke point, and the emitter broadcasts the event to
 * the global observers once per emitted event — independently of the per-engine active/local
 * registries.</p>
 *
 * <p>This is intended for cross-cutting sinks that must observe the whole platform without any
 * per-source wiring — event connectors, global logging, metrics — so an application can attach a
 * sink once (or, as the events connectors do, have it self-register) and capture everything.</p>
 *
 * <h2>Encapsulation &amp; safety</h2>
 * <ul>
 *   <li>The backing set is a {@code private static final}
 *       {@link ConcurrentHashMap#newKeySet() ConcurrentHashMap.newKeySet()}; it is never exposed
 *       (no getter returns the set), so there is no leaked mutable static state.</li>
 *   <li>{@link #fire(ObservableEvent)} is <b>exception-isolated per observer</b>: a throwing
 *       observer is logged at warn level and never breaks delivery to the others, and the method
 *       itself never throws.</li>
 * </ul>
 *
 * @since 3.0.0-ALPHA04
 */
public final class GlobalObservers {

	private static final Logger LOG = Logger.getLogger(GlobalObservers.class);

	private static final Set<IObserver<ObservableEvent>> OBSERVERS = ConcurrentHashMap.newKeySet();

	private GlobalObservers() {
		// utility holder — no instances
	}

	/**
	 * Register a firehose observer. Once added it receives every event broadcast through
	 * {@link ObservabilityEmitter}. A {@code null} observer is ignored.
	 *
	 * @param observer the observer to add
	 */
	public static void addObserver(IObserver<ObservableEvent> observer) {
		if (observer != null) {
			OBSERVERS.add(observer);
		}
	}

	/**
	 * Remove a previously registered firehose observer. A {@code null} or unknown observer is
	 * ignored.
	 *
	 * @param observer the observer to remove
	 */
	public static void removeObserver(IObserver<ObservableEvent> observer) {
		if (observer != null) {
			OBSERVERS.remove(observer);
		}
	}

	/**
	 * Broadcast the event to every registered firehose observer. Exceptions thrown by an observer
	 * are caught and logged at warn level; delivery continues with the remaining observers. This
	 * method never throws.
	 *
	 * @param event the event to broadcast; {@code null} is ignored
	 */
	public static void fire(ObservableEvent event) {
		if (event == null || OBSERVERS.isEmpty()) {
			return;
		}
		for (IObserver<ObservableEvent> observer : OBSERVERS) {
			try {
				observer.onEvent(event);
			} catch (RuntimeException ex) {
				LOG.warn("Global observer {} failed on event {}: {}",
						observer.getClass().getName(), event, ex.getMessage());
			}
		}
	}
}
