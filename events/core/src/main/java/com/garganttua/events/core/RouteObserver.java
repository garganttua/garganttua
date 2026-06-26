package com.garganttua.events.core;

import java.util.UUID;

import com.garganttua.core.observability.GlobalObservers;
import com.garganttua.core.observability.ObservabilityEmitter;
import com.garganttua.core.observability.ObservableRegistry;
import com.garganttua.core.workflow.IWorkflow;
import com.garganttua.core.workflow.WorkflowInput;
import com.garganttua.events.api.Exchange;

/**
 * Runs a route's message-processing {@link IWorkflow} under an observability scope, emitting
 * {@code events:route:<uuid>} Start/End/Error events around the execution.
 *
 * <p>This is the events-side equivalent of how garganttua-api's {@code Domain} wraps its
 * {@code doInvoke} call: each routed message becomes one observed unit of work, carrying the
 * {@link Exchange} as the End/Error payload (the business payload, mirroring api's {@code IEvent}).
 * The route's already-instrumented workflow nests under the same {@code executionId} via the
 * shared {@link ObservabilityEmitter} session, and the process-global firehose receives every
 * emitted event exactly once.</p>
 *
 * <p>A fast path keeps the hot path free of overhead: when neither the engine's local
 * {@link ObservableRegistry} nor the {@link GlobalObservers} firehose has any observer, the
 * workflow is executed directly with no scope opened and no event built.</p>
 *
 * @since 3.0.0-ALPHA04
 */
final class RouteObserver {

	private final ObservableRegistry observableRegistry;

	/**
	 * Creates a route observer bound to the engine's local observability registry.
	 *
	 * @param observableRegistry the engine registry observers are attached to via
	 *                           {@code Events.addObserver}; events also reach the global firehose
	 */
	RouteObserver(ObservableRegistry observableRegistry) {
		this.observableRegistry = observableRegistry;
	}

	/**
	 * Executes {@code workflow} for one routed message, emitting {@code events:route:<routeUuid>}
	 * Start/End/Error events when an observer (local or global) is listening, otherwise running the
	 * workflow directly. A {@link RuntimeException} thrown by the workflow is rethrown unchanged
	 * after firing the Error event.
	 *
	 * @param routeUuid the route UUID, used as the event source suffix
	 * @param workflow  the route's message-processing workflow
	 * @param input     the workflow input wrapping the {@code exchange}
	 * @param exchange  the message envelope carried as the End/Error event payload
	 */
	@SuppressFBWarnings(value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
			justification = "Observability pattern: the original RuntimeException is rethrown unchanged "
					+ "after firing the events:route Error event, to propagate the failure to the consumer.")
	void execute(String routeUuid, IWorkflow workflow, WorkflowInput input, Exchange exchange) {
		if (!this.observableRegistry.hasObservers() && !GlobalObservers.hasObservers()) {
			workflow.execute(input);
			return;
		}

		UUID executionUuid = UUID.randomUUID();
		String source = "events:route:" + routeUuid;
		try (var scope = ObservabilityEmitter.open(this.observableRegistry, executionUuid)) {
			scope.fireStart(source);
			try {
				workflow.execute(input);
				scope.fireEnd(source, null, exchange);
			} catch (RuntimeException e) {
				scope.fireError(source, e, exchange);
				throw e;
			}
		}
	}
}
