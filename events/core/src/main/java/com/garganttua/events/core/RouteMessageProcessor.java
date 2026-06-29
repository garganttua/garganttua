package com.garganttua.events.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.garganttua.core.observability.Logger;
import com.garganttua.core.workflow.IWorkflow;
import com.garganttua.core.workflow.WorkflowInput;
import com.garganttua.core.workflow.WorkflowResult;
import com.garganttua.events.api.Exchange;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.IDistributedLock;
import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.RouteDef;
import com.garganttua.events.api.context.RouteExceptionsDef;
import com.garganttua.events.api.context.RouteSyncDef;
import com.garganttua.events.api.context.SubscriptionDef;
import com.garganttua.events.api.exceptions.ConnectorException;
import com.garganttua.events.api.exceptions.EventsException;

/**
 * Bridges an inbound {@link IConsumer} to a route's compiled {@link IWorkflow}: for each raw message
 * it builds an {@link Exchange}, dispatches the workflow execution through a per-route
 * {@link RouteDispatcher} (sequential when the dataflow guarantees order, otherwise on a worker
 * pool), and on failure dead-letters the exchange to the route's error subscription — all under the
 * route's synchronization lock when one is configured and resolvable.
 *
 * <p>Extracted from {@code Events} as a cohesive per-message processing collaborator so the engine
 * class stays under the file-size gate. It shares the engine's {@link RouteObserver} (for correlated
 * route events) and the engine's dispatcher registry (so dispatchers are closed on stop).</p>
 */
final class RouteMessageProcessor {

	private static final Logger log = Logger.getLogger(RouteMessageProcessor.class);

	private final RouteObserver routeObserver;
	private final List<RouteDispatcher> routeDispatchers;

	RouteMessageProcessor(RouteObserver routeObserver, List<RouteDispatcher> routeDispatchers) {
		this.routeObserver = routeObserver;
		this.routeDispatchers = routeDispatchers;
	}

	/**
	 * Starts consuming on the given consumer, feeding each received message through the route workflow.
	 *
	 * @param consumer the inbound consumer to start
	 * @param workflow the compiled route workflow
	 * @param fromSub  the inbound subscription
	 * @param fromDf   the inbound dataflow (may be {@code null})
	 * @param routeDef the route definition
	 * @param runtime  the cluster runtime
	 */
	void runConsumer(IConsumer consumer, IWorkflow workflow, SubscriptionDef fromSub,
			DataflowDef fromDf, RouteDef routeDef, ClusterRuntime runtime) {
		int concurrency = fromSub.consumerConfiguration() != null
				? fromSub.consumerConfiguration().concurrency() : 1;
		boolean guaranteeOrder = fromDf != null && fromDf.garanteeOrder();
		RouteDispatcher dispatcher = new RouteDispatcher(routeDef.uuid(), concurrency, guaranteeOrder);
		routeDispatchers.add(dispatcher);
		RouteHandlers handlers = resolveHandlers(routeDef, runtime);
		try {
			consumer.start(rawBytes -> {
				Exchange exchange = Exchange.create(
						fromSub.connector(), fromSub.topic(),
						fromDf != null ? fromDf.uuid() : null, rawBytes);
				Map<String, Object> params = new HashMap<>();
				params.put("exchange", exchange);
				WorkflowInput input = WorkflowInput.of(exchange, params);
				// Sequential (ordered) inline, or submitted to the worker pool (parallel).
				dispatcher.dispatch(() -> processMessage(routeDef.uuid(), workflow, input, exchange, handlers));
			});
		} catch (Exception e) {
			log.error("Consumer thread error for route {}", routeDef.uuid(), e);
		}
	}

	/**
	 * Processes one routed message under the route's synchronization lock (when configured and
	 * resolvable) and routes the exchange to the error subscription when the workflow fails or
	 * throws — honouring {@code RouteDef.synchronization} and {@code RouteDef.exceptions}.
	 */
	private void processMessage(String routeUuid, IWorkflow workflow, WorkflowInput input,
			Exchange exchange, RouteHandlers handlers) {
		acquire(routeUuid, handlers);
		try {
			WorkflowResult result = routeObserver.execute(routeUuid, workflow, input, exchange);
			if (result == null || !result.isSuccess()) {
				routeToError(routeUuid, exchange, handlers.errorProducer());
			}
		} catch (RuntimeException e) {
			log.error("Route {} execution failed", routeUuid, e);
			routeToError(routeUuid, exchange, handlers.errorProducer());
		} finally {
			release(routeUuid, handlers);
		}
	}

	private void acquire(String routeUuid, RouteHandlers handlers) {
		if (handlers.lock() == null) {
			return;
		}
		try {
			handlers.lock().lock(handlers.lockObject());
		} catch (EventsException e) {
			log.warn("Route {}: failed to acquire lock '{}': {}", routeUuid,
					handlers.lock().getName(), e.getMessage());
		}
	}

	private void release(String routeUuid, RouteHandlers handlers) {
		if (handlers.lock() == null) {
			return;
		}
		try {
			handlers.lock().unlock(handlers.lockObject());
		} catch (EventsException e) {
			log.warn("Route {}: failed to release lock '{}': {}", routeUuid,
					handlers.lock().getName(), e.getMessage());
		}
	}

	private void routeToError(String routeUuid, Exchange exchange, IProducer errorProducer) {
		if (errorProducer == null) {
			return;
		}
		try {
			errorProducer.publish(exchange.value());
			log.debug("Route {}: failed exchange routed to its error subscription", routeUuid);
		} catch (ConnectorException e) {
			log.error("Route {}: could not route failed exchange to the error subscription", routeUuid, e);
		}
	}

	/**
	 * Resolves the route's per-message error producer ({@code RouteDef.exceptions.to}) and
	 * synchronization lock ({@code RouteDef.synchronization}). The lock is looked up in the cluster
	 * runtime's lock registry; it stays {@code null} until a lock provider populates that registry.
	 */
	private RouteHandlers resolveHandlers(RouteDef routeDef, ClusterRuntime runtime) {
		IProducer errorProducer = resolveErrorProducer(routeDef, runtime);
		IDistributedLock lock = null;
		String lockObject = null;
		RouteSyncDef sync = routeDef.synchronization();
		if (sync != null && sync.lock() != null) {
			lock = runtime.getLocks().get(sync.lock());
			lockObject = sync.lockObject();
			if (lock == null) {
				log.warn("Route {}: synchronization lock '{}' is not resolvable (no lock provider "
						+ "registered); the route runs without synchronization", routeDef.uuid(), sync.lock());
			}
		}
		return new RouteHandlers(errorProducer, lock, lockObject);
	}

	private IProducer resolveErrorProducer(RouteDef routeDef, ClusterRuntime runtime) {
		RouteExceptionsDef exceptions = routeDef.exceptions();
		if (exceptions == null || exceptions.to() == null) {
			return null;
		}
		SubscriptionDef errorSub = runtime.getSubscriptions().get(exceptions.to());
		if (errorSub == null) {
			log.warn("Route {}: error subscription '{}' not found; failures will not be routed",
					routeDef.uuid(), exceptions.to());
			return null;
		}
		IConnector connector = runtime.getConnectors().get(errorSub.connector());
		DataflowDef df = runtime.getDataflows().get(errorSub.dataflow());
		if (connector == null || df == null) {
			return null;
		}
		return connector.createProducer(errorSub, df);
	}

	/** Per-route runtime handlers: the error-subscription producer and the synchronization lock. */
	private record RouteHandlers(IProducer errorProducer, IDistributedLock lock, String lockObject) {}
}
