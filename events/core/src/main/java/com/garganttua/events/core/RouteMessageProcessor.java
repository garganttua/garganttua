package com.garganttua.events.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.garganttua.core.injection.BeanReference;
import com.garganttua.core.injection.IInjectionContext;
import com.garganttua.core.mutex.IMutex;
import com.garganttua.core.mutex.IMutexManager;
import com.garganttua.core.mutex.InterruptibleLeaseMutex;
import com.garganttua.core.mutex.MutexException;
import com.garganttua.core.mutex.MutexName;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.workflow.IWorkflow;
import com.garganttua.core.workflow.WorkflowInput;
import com.garganttua.core.workflow.WorkflowResult;
import com.garganttua.events.api.Exchange;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.RouteDef;
import com.garganttua.events.api.context.RouteExceptionsDef;
import com.garganttua.events.api.context.RouteSyncDef;
import com.garganttua.events.api.context.SubscriptionDef;
import com.garganttua.events.api.exceptions.ConnectorException;

/**
 * Bridges an inbound {@link IConsumer} to a route's compiled {@link IWorkflow}: for each raw message
 * it builds an {@link Exchange}, dispatches the workflow execution through a per-route
 * {@link RouteDispatcher} (sequential when the dataflow guarantees order, otherwise on a worker
 * pool), and on failure dead-letters the exchange to the route's error subscription — all under the
 * route's synchronization mutex when one is configured.
 *
 * <p>Synchronization reuses garganttua-core's {@link IMutexManager} / {@link IMutex} rather than any
 * events-local lock abstraction: a route's {@code synchronization} resolves to a {@link MutexName}
 * and the per-message workflow runs inside {@link IMutex#acquire(IMutex.ThrowingFunction)} so the
 * mutex blocks, runs, and auto-releases. A plain {@code lock} name uses the default
 * {@link InterruptibleLeaseMutex}; a qualified {@code Type::name} lock selects a registered
 * distributed mutex factory, so distributed locking plugs in through the core mutex SPI with no
 * events-side mechanism.</p>
 *
 * <p>Extracted from {@code Events} as a cohesive per-message processing collaborator so the engine
 * class stays under the file-size gate. It shares the engine's {@link RouteObserver} (for correlated
 * route events) and the engine's dispatcher registry (so dispatchers are closed on stop).</p>
 */
final class RouteMessageProcessor {

	private static final Logger log = Logger.getLogger(RouteMessageProcessor.class);

	private final RouteObserver routeObserver;
	private final List<RouteDispatcher> routeDispatchers;
	private final IMutexManager mutexManager;
	private final IInjectionContext injectionContext;

	RouteMessageProcessor(RouteObserver routeObserver, List<RouteDispatcher> routeDispatchers,
			IMutexManager mutexManager, IInjectionContext injectionContext) {
		this.routeObserver = routeObserver;
		this.routeDispatchers = routeDispatchers;
		this.mutexManager = mutexManager;
		this.injectionContext = injectionContext;
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
	 * Processes one routed message under the route's synchronization mutex (when configured) and
	 * routes the exchange to the error subscription when the workflow fails or throws — honouring
	 * {@code RouteDef.synchronization} and {@code RouteDef.exceptions}.
	 */
	private void processMessage(String routeUuid, IWorkflow workflow, WorkflowInput input,
			Exchange exchange, RouteHandlers handlers) {
		if (handlers.mutex() == null) {
			executeAndHandle(routeUuid, workflow, input, exchange, handlers);
			return;
		}
		try {
			handlers.mutex().acquire(() -> {
				executeAndHandle(routeUuid, workflow, input, exchange, handlers);
				return null;
			});
		} catch (MutexException e) {
			log.error("Route {}: synchronized execution failed under its mutex", routeUuid, e);
			routeToError(routeUuid, exchange, handlers.errorProducer());
		}
	}

	/** Runs the workflow and dead-letters on failure; never throws (so it is safe inside a mutex). */
	private void executeAndHandle(String routeUuid, IWorkflow workflow, WorkflowInput input,
			Exchange exchange, RouteHandlers handlers) {
		try {
			WorkflowResult result = routeObserver.execute(routeUuid, workflow, input, exchange);
			if (result == null || !result.isSuccess()) {
				routeToError(routeUuid, exchange, handlers.errorProducer());
			}
		} catch (RuntimeException e) {
			log.error("Route {} execution failed", routeUuid, e);
			routeToError(routeUuid, exchange, handlers.errorProducer());
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
	 * synchronization mutex ({@code RouteDef.synchronization}). The mutex is obtained from the core
	 * {@link IMutexManager}; it stays {@code null} when no synchronization is configured.
	 */
	private RouteHandlers resolveHandlers(RouteDef routeDef, ClusterRuntime runtime) {
		IProducer errorProducer = resolveErrorProducer(routeDef, runtime);
		IMutex mutex = resolveMutex(routeDef);
		return new RouteHandlers(errorProducer, mutex);
	}

	/**
	 * Resolves the route's synchronization mutex, or {@code null} when the route declares no
	 * synchronization. A {@code lockBean} reference resolves an {@link IMutex} bean from the injection
	 * context (the DSL {@code synchronization(IMutex)} / {@code synchronization(ISupplierBuilder)} /
	 * {@code synchronizationBean(String)} forms); otherwise a {@code lock} name resolves through the
	 * core {@link IMutexManager}. A malformed or unresolvable lock is logged and the route runs
	 * unsynchronized rather than failing to start.
	 *
	 * <p>Package-private for unit testing the resolution branches directly.</p>
	 */
	IMutex resolveMutex(RouteDef routeDef) {
		RouteSyncDef sync = routeDef.synchronization();
		if (sync == null) {
			return null;
		}
		if (sync.lockBean() != null && !sync.lockBean().isBlank()) {
			return resolveMutexBean(routeDef.uuid(), sync.lockBean());
		}
		if (sync.lock() == null || sync.lock().isBlank() || mutexManager == null) {
			return null;
		}
		try {
			return mutexManager.mutex(toMutexName(sync));
		} catch (RuntimeException e) {
			log.warn("Route {}: cannot resolve synchronization mutex '{}': {}; running without "
					+ "synchronization", routeDef.uuid(), sync.lock(), e.getMessage());
			return null;
		}
	}

	/**
	 * Resolves an {@link IMutex} bean from the injection context by the given reference. The reference's
	 * {@code #name} segment (or the whole token when it names no {@code #name}) is used as the bean
	 * name. A missing context, absent bean, or resolution error degrades gracefully to {@code null}
	 * (route runs unsynchronized), matching the name-based path.
	 */
	private IMutex resolveMutexBean(String routeUuid, String beanReference) {
		if (injectionContext == null) {
			log.warn("Route {}: no injection context to resolve synchronization mutex bean '{}'; "
					+ "running without synchronization", routeUuid, beanReference);
			return null;
		}
		try {
			String name = BeanReference.extractName(beanReference).orElse(beanReference);
			BeanReference<IMutex> query = new BeanReference<>(
					IClass.getClass(IMutex.class), Optional.empty(), Optional.of(name), Set.of());
			IMutex mutex = injectionContext.queryBean(query).orElse(null);
			if (mutex == null) {
				log.warn("Route {}: synchronization mutex bean '{}' not found; running without "
						+ "synchronization", routeUuid, beanReference);
			}
			return mutex;
		} catch (Exception e) {
			log.warn("Route {}: cannot resolve synchronization mutex bean '{}': {}; running without "
					+ "synchronization", routeUuid, beanReference, e.getMessage());
			return null;
		}
	}

	/**
	 * Maps {@code synchronization(lock, lockObject)} to a {@link MutexName}. A qualified {@code lock}
	 * ({@code Type::name}) selects a registered mutex factory type; a plain {@code lock} uses the
	 * default {@link InterruptibleLeaseMutex}. A non-blank {@code lockObject} narrows the name (e.g.
	 * per tenant/entity), so independent keys serialize independently under one logical lock.
	 */
	private MutexName toMutexName(RouteSyncDef sync) {
		IClass<? extends IMutex> type;
		String name;
		if (sync.lock().contains(MutexName.SEPARATOR)) {
			MutexName qualified = MutexName.fromString(sync.lock());
			type = qualified.type();
			name = qualified.name();
		} else {
			type = IClass.getClass(InterruptibleLeaseMutex.class);
			name = sync.lock();
		}
		if (sync.lockObject() != null && !sync.lockObject().isBlank()) {
			name = name + ":" + sync.lockObject();
		}
		return new MutexName(type, name);
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

	/** Per-route runtime handlers: the error-subscription producer and the synchronization mutex. */
	private record RouteHandlers(IProducer errorProducer, IMutex mutex) {}
}
