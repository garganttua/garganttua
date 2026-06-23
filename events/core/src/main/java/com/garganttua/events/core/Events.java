package com.garganttua.events.core;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.garganttua.core.lifecycle.AbstractLifecycle;
import com.garganttua.core.lifecycle.ILifecycle;
import com.garganttua.core.lifecycle.LifecycleException;
import com.garganttua.core.workflow.IWorkflow;
import com.garganttua.core.workflow.WorkflowInput;
import com.garganttua.core.workflow.dsl.IWorkflowBuilder;
import com.garganttua.core.workflow.dsl.WorkflowsBuilder;
import com.garganttua.events.api.ConnectorContext;
import com.garganttua.events.api.Exchange;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.IEvents;
import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.context.ConnectorDef;
import com.garganttua.events.api.context.ContextDef;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.RouteDef;
import com.garganttua.events.api.context.RouteStageDef;
import com.garganttua.events.api.context.SubscriptionDef;
import com.garganttua.core.dsl.DslException;
import com.garganttua.core.dsl.IObservableBuilder;
import com.garganttua.core.expression.dsl.IExpressionContextBuilder;
import com.garganttua.core.injection.BeanReference;
import com.garganttua.core.injection.IInjectionContext;
import com.garganttua.core.injection.context.dsl.IInjectionContextBuilder;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.events.api.connectors.annotations.Connector;

public class Events extends AbstractLifecycle implements IEvents {

	private static final Logger log = Logger.getLogger(Events.class);

	private final String assetId;
	private final List<ContextDef> contexts;
	private final Map<String, IClass<? extends IConnector>> connectorRegistry;
	private final IObservableBuilder<?, ?> injectionContextBuilder;
	private final IObservableBuilder<?, ?> expressionContextBuilder;

	private final Map<String, Map<String, ClusterRuntime>> runtimes = new HashMap<>();
	private final List<Thread> consumerThreads = new ArrayList<>();
	private ExecutorService executorService;
	private IInjectionContext injectionContext;

	public Events(String assetId, List<ContextDef> contexts,
			Map<String, IClass<? extends IConnector>> connectorRegistry,
			IObservableBuilder<?, ?> injectionContextBuilder,
			IObservableBuilder<?, ?> expressionContextBuilder) {
		this.assetId = assetId;
		// Defensive copies: the engine owns immutable snapshots of the supplied
		// configuration; callers must not be able to mutate engine internals afterwards.
		this.contexts = new ArrayList<>(contexts);
		this.connectorRegistry = new HashMap<>(connectorRegistry);
		this.injectionContextBuilder = injectionContextBuilder;
		this.expressionContextBuilder = expressionContextBuilder;
	}

	/**
	 * Wires the injection context the engine uses to resolve connectors as beans. Called by
	 * {@code EventsBuilder} after connector beans are registered and before the engine starts;
	 * when unset, {@link #initConnector} falls back to the reflective registry path.
	 *
	 * @param injectionContext the built injection context
	 */
	@SuppressFBWarnings(value = "EI_EXPOSE_REP2",
			justification = "The engine deliberately holds the live shared injection context to "
					+ "resolve connector beans; it is not a defensively copied value object.")
	public void setInjectionContext(IInjectionContext injectionContext) {
		this.injectionContext = injectionContext;
	}

	@Override
	public String getAssetId() {
		return this.assetId;
	}

	@Override
	public IReflection reflection() {
		return IClass.getReflection();
	}

	@Override
	protected ILifecycle doInit() throws LifecycleException {
		log.info("============================================");
		log.info("====== Starting Garganttua Events     ======");
		log.info("====== ASSET [{}]", assetId);
		log.info("============================================");

		this.executorService = Executors.newCachedThreadPool();

		for (ContextDef context : contexts) {
			initContext(context);
		}

		return this;
	}

	private void initContext(ContextDef context) throws LifecycleException {
		String tenantId = context.tenantId();
		String clusterId = context.clusterId();

		log.info("[{}][{}][{}] Initializing context", assetId, tenantId, clusterId);

		runtimes.computeIfAbsent(tenantId, k -> new HashMap<>());
		ClusterRuntime runtime = new ClusterRuntime(context);
		runtimes.get(tenantId).put(clusterId, runtime);

		registerTopicsAndDataflows(context, runtime, tenantId, clusterId);

		if (context.connectors() != null) {
			for (ConnectorDef connDef : context.connectors()) {
				initConnector(connDef, runtime, tenantId, clusterId);
			}
		}

		if (context.subscriptions() != null) {
			for (SubscriptionDef sub : context.subscriptions()) {
				runtime.getSubscriptions().put(sub.id(), sub);
				log.info("[{}][{}][{}] Subscription {} registered", assetId, tenantId, clusterId, sub.id());
			}
		}

		if (context.routes() != null) {
			for (RouteDef routeDef : context.routes()) {
				IWorkflow workflow = buildRouteWorkflow(routeDef, runtime, tenantId, clusterId);
				runtime.getRouteWorkflows().put(routeDef.uuid(), workflow);
				log.info("[{}][{}][{}] Route {} workflow built", assetId, tenantId, clusterId, routeDef.uuid());
			}
		}
	}

	private void registerTopicsAndDataflows(ContextDef context, ClusterRuntime runtime,
			String tenantId, String clusterId) {
		if (context.topics() != null) {
			context.topics().forEach(topic -> {
				runtime.getTopics().put(topic.ref(), topic);
				log.info("[{}][{}][{}] Topic {} registered", assetId, tenantId, clusterId, topic.ref());
			});
		}

		if (context.dataflows() != null) {
			context.dataflows().forEach(df -> {
				runtime.getDataflows().put(df.uuid(), df);
				log.info("[{}][{}][{}] Dataflow {} registered", assetId, tenantId, clusterId, df.uuid());
			});
		}
	}

	private void initConnector(ConnectorDef connDef, ClusterRuntime runtime,
			String tenantId, String clusterId) throws LifecycleException {
		try {
			IConnector instance = resolveConnector(connDef);
			ConnectorContext ctx = new ConnectorContext(assetId, tenantId, clusterId);
			Map<String, String> config = connDef.configuration() != null
					? new HashMap<>(connDef.configuration()) : new HashMap<>();
			config.put("name", connDef.name());
			instance.configure(config, ctx);
			runtime.getConnectors().put(connDef.name(), instance);
			log.info("[{}][{}][{}] Connector {} ({}) configured", assetId, tenantId, clusterId,
					connDef.name(), connDef.type());
		} catch (LifecycleException e) {
			throw e;
		} catch (Exception e) {
			throw new LifecycleException(new RuntimeException("Failed to create connector: " + connDef.name(), e));
		}
	}

	/**
	 * Resolves a connector instance: bean-first (from the injection context, by name
	 * {@code connector:type:version} and the {@code @Connector} qualifier), falling back to the
	 * reflective registry path (a fresh instance per definition through the reflection facade) when
	 * no context is wired or the bean is absent — so the engine works with or without injection.
	 */
	private IConnector resolveConnector(ConnectorDef connDef) throws LifecycleException {
		String key = connDef.type() + ":" + connDef.version();
		Optional<IConnector> bean = resolveConnectorBean(key);
		if (bean.isPresent()) {
			log.debug("Connector {} resolved from injection context bean 'connector:{}'", key, key);
			return bean.get();
		}
		IClass<? extends IConnector> connectorClass = connectorRegistry.get(key);
		if (connectorClass == null) {
			throw new LifecycleException("Connector not found: " + key);
		}
		// Reflective fallback: new instance per connector definition through the garganttua-reflection
		// facade (provider-agnostic: runtime or AOT), never java.lang.reflect directly.
		try {
			return connectorClass.getDeclaredConstructor().newInstance();
		} catch (ReflectiveOperationException e) {
			throw new LifecycleException(
					new RuntimeException("Failed to instantiate connector: " + key, e));
		}
	}

	private Optional<IConnector> resolveConnectorBean(String key) {
		if (injectionContext == null) {
			return Optional.empty();
		}
		IClass<? extends Annotation> qualifier = IClass.getClass(Connector.class);
		BeanReference<IConnector> query = new BeanReference<>(
				IClass.getClass(IConnector.class),
				Optional.empty(), Optional.of("connector:" + key), Set.of(qualifier));
		try {
			return injectionContext.queryBean(query);
		} catch (Exception e) {
			log.warn("Bean resolution failed for connector {}, falling back to registry: {}",
					key, e.getMessage());
			return Optional.empty();
		}
	}

	private IWorkflow buildRouteWorkflow(RouteDef routeDef, ClusterRuntime runtime,
			String tenantId, String clusterId) throws LifecycleException {
		try {
			SubscriptionDef fromSub = runtime.getSubscriptions().get(routeDef.from());
			if (fromSub == null) {
				throw new LifecycleException("From subscription not found: " + routeDef.from());
			}

			IWorkflowBuilder wb = WorkflowsBuilder.builder()
					.provide(injectionContextBuilder)
					.provide(expressionContextBuilder)
					.workflow("route-" + routeDef.uuid())
					.variable("assetId", assetId)
					.variable("tenantId", tenantId)
					.variable("clusterId", clusterId)
					.variable("subscriptionId", fromSub.id())
					.inlineAll();

			DataflowDef fromDf = runtime.getDataflows().get(fromSub.dataflow());
			if (fromDf != null) {
				wb.variable("version", fromDf.version());
				wb.variable("dataflowUuid", fromDf.uuid());
			}

			wb.variable("connectorName", fromSub.connector());

			registerProducer(routeDef, runtime, wb);
			registerConsumer(routeDef, runtime, fromSub);
			addStages(routeDef, wb);

			return wb.build();
		} catch (DslException e) {
			throw new LifecycleException(new RuntimeException("Failed to build route workflow: " + routeDef.uuid(), e));
		}
	}

	private void registerProducer(RouteDef routeDef, ClusterRuntime runtime, IWorkflowBuilder wb) {
		if (routeDef.to() == null) {
			return;
		}
		SubscriptionDef toSub = runtime.getSubscriptions().get(routeDef.to());
		if (toSub == null) {
			return;
		}
		IConnector toConnector = runtime.getConnectors().get(toSub.connector());
		DataflowDef toDf = runtime.getDataflows().get(toSub.dataflow());
		if (toConnector != null && toDf != null) {
			IProducer producer = toConnector.createProducer(toSub, toDf);
			runtime.getProducers().put(routeDef.uuid() + ":" + toSub.id(), producer);
			wb.variable("producer", producer);
			wb.variable("topicRef", toSub.topic());
		}
	}

	private void registerConsumer(RouteDef routeDef, ClusterRuntime runtime, SubscriptionDef fromSub) {
		IConnector fromConnector = runtime.getConnectors().get(fromSub.connector());
		DataflowDef fromDfForConsumer = runtime.getDataflows().get(fromSub.dataflow());
		if (fromConnector != null && fromDfForConsumer != null) {
			IConsumer consumer = fromConnector.createConsumer(fromSub, fromDfForConsumer);
			runtime.getConsumers().put(routeDef.uuid() + ":" + fromSub.id(), consumer);
		}
	}

	private void addStages(RouteDef routeDef, IWorkflowBuilder wb) throws DslException {
		if (routeDef.stages() == null) {
			return;
		}
		for (RouteStageDef stageDef : routeDef.stages()) {
			String scriptContent = "exchange <- " + stageDef.expression();

			var stageBuilder = wb.stage(stageDef.name());
			var scriptBuilder = stageBuilder.script(scriptContent)
					.name(stageDef.name())
					.inline();

			if (stageDef.condition() != null) {
				scriptBuilder.when(stageDef.condition());
			}
			if (stageDef.catchExpression() != null) {
				scriptBuilder.catch_(stageDef.catchExpression());
			}
			if (stageDef.catchDownstreamExpression() != null) {
				scriptBuilder.catchDownstream(stageDef.catchDownstreamExpression());
			}

			scriptBuilder.up(); // → stage
			stageBuilder.up();  // → workflow
		}
	}

	@Override
	protected ILifecycle doStart() throws LifecycleException {
		log.info("==== STARTING CONNECTORS ====");

		for (Map.Entry<String, Map<String, ClusterRuntime>> tenantEntry : runtimes.entrySet()) {
			for (Map.Entry<String, ClusterRuntime> clusterEntry : tenantEntry.getValue().entrySet()) {
				startConnectors(clusterEntry.getValue(), tenantEntry.getKey(), clusterEntry.getKey());
				startConsumers(clusterEntry.getValue(), tenantEntry.getKey(), clusterEntry.getKey());
			}
		}

		log.info("==== GARGANTTUA EVENTS STARTED ====");
		return this;
	}

	private void startConnectors(ClusterRuntime runtime, String tenantId, String clusterId)
			throws LifecycleException {
		for (Map.Entry<String, IConnector> connEntry : runtime.getConnectors().entrySet()) {
			try {
				connEntry.getValue().onInit();
				connEntry.getValue().onStart();
				log.info("[{}][{}][{}] Connector {} started", assetId, tenantId, clusterId, connEntry.getKey());
			} catch (Exception e) {
				throw new LifecycleException(new RuntimeException("Failed to start connector: " + connEntry.getKey(), e));
			}
		}
	}

	private void startConsumers(ClusterRuntime runtime, String tenantId, String clusterId) {
		ContextDef context = runtime.getContext();
		if (context.routes() == null) {
			return;
		}
		for (RouteDef routeDef : context.routes()) {
			String consumerKey = routeDef.uuid() + ":" + routeDef.from();
			IConsumer consumer = runtime.getConsumers().get(consumerKey);
			IWorkflow workflow = runtime.getRouteWorkflows().get(routeDef.uuid());

			if (consumer != null && workflow != null) {
				SubscriptionDef fromSub = runtime.getSubscriptions().get(routeDef.from());
				DataflowDef fromDf = runtime.getDataflows().get(fromSub.dataflow());

				Thread consumerThread = new Thread(
						() -> runConsumer(consumer, workflow, fromSub, fromDf, routeDef),
						"consumer-" + routeDef.uuid());
				consumerThread.setDaemon(true);
				consumerThread.start();
				consumerThreads.add(consumerThread);

				log.info("[{}][{}][{}] Route {} consumer started", assetId, tenantId, clusterId, routeDef.uuid());
			}
		}
	}

	private void runConsumer(IConsumer consumer, IWorkflow workflow, SubscriptionDef fromSub,
			DataflowDef fromDf, RouteDef routeDef) {
		try {
			consumer.start(rawBytes -> {
				Exchange exchange = Exchange.create(
						fromSub.connector(), fromSub.topic(),
						fromDf != null ? fromDf.uuid() : null, rawBytes);
				Map<String, Object> params = new HashMap<>();
				params.put("exchange", exchange);
				WorkflowInput input = WorkflowInput.of(exchange, params);
				workflow.execute(input);
			});
		} catch (Exception e) {
			log.error("Consumer thread error for route {}", routeDef.uuid(), e);
		}
	}

	@Override
	protected ILifecycle doStop() throws LifecycleException {
		log.info("==== STOPPING GARGANTTUA EVENTS ====");

		// Stop consumers
		for (Map.Entry<String, Map<String, ClusterRuntime>> tenantEntry : runtimes.entrySet()) {
			for (Map.Entry<String, ClusterRuntime> clusterEntry : tenantEntry.getValue().entrySet()) {
				stopRuntime(clusterEntry.getValue());
			}
		}

		for (Thread t : consumerThreads) {
			t.interrupt();
		}
		consumerThreads.clear();

		if (executorService != null) {
			executorService.shutdown();
		}

		log.info("==== GARGANTTUA EVENTS STOPPED ====");
		return this;
	}

	private void stopRuntime(ClusterRuntime runtime) {
		for (IConsumer consumer : runtime.getConsumers().values()) {
			try {
				consumer.stop();
			} catch (Exception e) {
				log.warn("Error stopping consumer", e);
			}
		}
		for (IProducer producer : runtime.getProducers().values()) {
			try {
				producer.stop();
			} catch (Exception e) {
				log.warn("Error stopping producer", e);
			}
		}
		for (IConnector connector : runtime.getConnectors().values()) {
			try {
				connector.onStop();
			} catch (Exception e) {
				log.warn("Error stopping connector", e);
			}
		}
	}

	@Override
	protected ILifecycle doFlush() throws LifecycleException {
		runtimes.clear();
		consumerThreads.clear();
		return this;
	}
}
