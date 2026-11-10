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
 *       observer is logged at warn level and never breaks delivery to the others.</li>
 *   <li>An observer registered with {@link #addBlockingObserver} is the one exception, chosen
 *       explicitly: its failure is RAISED, once every observer has been offered the event, so that
 *       the observed operation can refuse to go on unobserved — an audit trail that must not miss an
 *       access, for instance. An ordinary observer can never break an operation.</li>
 * </ul>
 *
 * @since 3.0.0-ALPHA04
 */
public final class GlobalObservers {

	private static final Logger LOG = Logger.getLogger(GlobalObservers.class);

	private static final Set<IObserver<ObservableEvent>> OBSERVERS = ConcurrentHashMap.newKeySet();

	/** Observers whose failure the observed operation must know: see {@link #addBlockingObserver}. */
	private static final Set<IObserver<ObservableEvent>> BLOCKING = ConcurrentHashMap.newKeySet();

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
	 * Register a BLOCKING firehose observer: like {@link #addObserver}, except that when it throws, the
	 * failure is not swallowed. {@link #fire} first offers the event to every observer, then raises an
	 * {@link ObserverFailureException} — and the operation that emitted the event fails with it.
	 *
	 * <p>
	 * For a sink without which the operation must not happen at all, never for logging or metrics. A
	 * blocking observer runs on the emitting thread and its latency is the operation's.
	 * </p>
	 *
	 * @param observer the observer to add; {@code null} is ignored
	 * @since 3.0.0-ALPHA34
	 */
	public static void addBlockingObserver(IObserver<ObservableEvent> observer) {
		if (observer != null) {
			BLOCKING.add(observer);
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
			BLOCKING.remove(observer);
		}
	}

	/**
	 * Reports whether any firehose observer is currently registered. Cheap (set emptiness check),
	 * so a hot path can use it together with a per-engine registry check to skip building and
	 * emitting observability events entirely when nothing — local or global — is listening.
	 *
	 * @return {@code true} if at least one global observer is registered, {@code false} otherwise
	 */
	public static boolean hasObservers() {
		return !OBSERVERS.isEmpty() || !BLOCKING.isEmpty();
	}

	/**
	 * Broadcast the event to every registered firehose observer. Exceptions thrown by an ordinary
	 * observer are caught and logged at warn level; delivery continues with the remaining observers.
	 *
	 * @param event the event to broadcast; {@code null} is ignored
	 * @throws ObserverFailureException when a BLOCKING observer threw — only after every observer has
	 *                                  been offered the event; never for an ordinary observer
	 */
	public static void fire(ObservableEvent event) {
		if (event == null || !hasObservers()) {
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
		ObserverFailureException failure = null;
		for (IObserver<ObservableEvent> observer : BLOCKING) {
			try {
				observer.onEvent(event);
			} catch (RuntimeException ex) {
				LOG.error("Blocking global observer {} failed on event {}: {}",
						observer.getClass().getName(), event, ex.getMessage());
				if (failure == null) {
					failure = new ObserverFailureException(event, ex);
				} else {
					failure.addSuppressed(ex);
				}
			}
		}
		if (failure != null) {
			throw failure;
		}
	}
}
