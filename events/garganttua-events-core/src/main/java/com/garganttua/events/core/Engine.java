package com.garganttua.events.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import com.garganttua.events.api.IEngine;
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
import com.garganttua.core.injection.context.dsl.IInjectionContextBuilder;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;

public class Engine extends AbstractLifecycle implements IEngine {

	private static final Logger log = Logger.getLogger(Engine.class);

	private final String assetId;
	private final List<ContextDef> contexts;
	private final Map<String, IConnector> connectorRegistry;
	private final IObservableBuilder<?, ?> injectionContextBuilder;
	private final IObservableBuilder<?, ?> expressionContextBuilder;

	private final Map<String, Map<String, ClusterRuntime>> runtimes = new HashMap<>();
	private final List<Thread> consumerThreads = new ArrayList<>();
	private ExecutorService executorService;

	public Engine(String assetId, List<ContextDef> contexts,
			Map<String, IConnector> connectorRegistry,
			IObservableBuilder<?, ?> injectionContextBuilder,
			IObservableBuilder<?, ?> expressionContextBuilder) {
		this.assetId = assetId;
		this.contexts = contexts;
		this.connectorRegistry = connectorRegistry;
		this.injectionContextBuilder = injectionContextBuilder;
		this.expressionContextBuilder = expressionContextBuilder;
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
			String tenantId = context.tenantId();
			String clusterId = context.clusterId();

			log.info("[{}][{}][{}] Initializing context", assetId, tenantId, clusterId);

			runtimes.computeIfAbsent(tenantId, k -> new HashMap<>());
			ClusterRuntime runtime = new ClusterRuntime(context);
			runtimes.get(tenantId).put(clusterId, runtime);

			// Register topics
			if (context.topics() != null) {
				context.topics().forEach(topic -> {
					runtime.getTopics().put(topic.ref(), topic);
					log.info("[{}][{}][{}] Topic {} registered", assetId, tenantId, clusterId, topic.ref());
				});
			}

			// Register dataflows
			if (context.dataflows() != null) {
				context.dataflows().forEach(df -> {
					runtime.getDataflows().put(df.uuid(), df);
					log.info("[{}][{}][{}] Dataflow {} registered", assetId, tenantId, clusterId, df.uuid());
				});
			}

			// Instantiate and configure connectors
			if (context.connectors() != null) {
				for (ConnectorDef connDef : context.connectors()) {
					String key = connDef.type() + ":" + connDef.version();
					IConnector connector = connectorRegistry.get(key);
					if (connector == null) {
						throw new LifecycleException("Connector not found: " + key);
					}

					// Create a new instance per connector definition
					try {
						IConnector instance = connector.getClass().getDeclaredConstructor().newInstance();
						ConnectorContext ctx = new ConnectorContext(assetId, tenantId, clusterId);
						Map<String, String> config = connDef.configuration() != null
								? new HashMap<>(connDef.configuration()) : new HashMap<>();
						config.put("name", connDef.name());
						instance.configure(config, ctx);
						runtime.getConnectors().put(connDef.name(), instance);
						log.info("[{}][{}][{}] Connector {} ({}) configured", assetId, tenantId, clusterId,
								connDef.name(), connDef.type());
					} catch (Exception e) {
						throw new LifecycleException(new RuntimeException("Failed to create connector: " + connDef.name(), e));
					}
				}
			}

			// Register subscriptions
			if (context.subscriptions() != null) {
				for (SubscriptionDef sub : context.subscriptions()) {
					runtime.getSubscriptions().put(sub.id(), sub);
					log.info("[{}][{}][{}] Subscription {} registered", assetId, tenantId, clusterId, sub.id());
				}
			}

			// Build route workflows
			if (context.routes() != null) {
				for (RouteDef routeDef : context.routes()) {
					IWorkflow workflow = buildRouteWorkflow(routeDef, runtime, tenantId, clusterId);
					runtime.getRouteWorkflows().put(routeDef.uuid(), workflow);
					log.info("[{}][{}][{}] Route {} workflow built", assetId, tenantId, clusterId, routeDef.uuid());
				}
			}
		}

		return this;
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

			// Create producer for "to" subscription if present
			if (routeDef.to() != null) {
				SubscriptionDef toSub = runtime.getSubscriptions().get(routeDef.to());
				if (toSub != null) {
					IConnector toConnector = runtime.getConnectors().get(toSub.connector());
					DataflowDef toDf = runtime.getDataflows().get(toSub.dataflow());
					if (toConnector != null && toDf != null) {
						IProducer producer = toConnector.createProducer(toSub, toDf);
						runtime.getProducers().put(routeDef.uuid() + ":" + toSub.id(), producer);
						wb.variable("producer", producer);
						wb.variable("topicRef", toSub.topic());
					}
				}
			}

			// Create consumer for "from" subscription
			IConnector fromConnector = runtime.getConnectors().get(fromSub.connector());
			DataflowDef fromDfForConsumer = runtime.getDataflows().get(fromSub.dataflow());
			if (fromConnector != null && fromDfForConsumer != null) {
				IConsumer consumer = fromConnector.createConsumer(fromSub, fromDfForConsumer);
				runtime.getConsumers().put(routeDef.uuid() + ":" + fromSub.id(), consumer);
			}

			// Add stages
			if (routeDef.stages() != null) {
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

			return wb.build();
		} catch (DslException e) {
			throw new LifecycleException(new RuntimeException("Failed to build route workflow: " + routeDef.uuid(), e));
		}
	}

	@Override
	protected ILifecycle doStart() throws LifecycleException {
		log.info("==== STARTING CONNECTORS ====");

		for (Map.Entry<String, Map<String, ClusterRuntime>> tenantEntry : runtimes.entrySet()) {
			for (Map.Entry<String, ClusterRuntime> clusterEntry : tenantEntry.getValue().entrySet()) {
				ClusterRuntime runtime = clusterEntry.getValue();

				// Init and start connectors
				for (Map.Entry<String, IConnector> connEntry : runtime.getConnectors().entrySet()) {
					try {
						connEntry.getValue().onInit();
						connEntry.getValue().onStart();
						log.info("[{}][{}][{}] Connector {} started", assetId,
								tenantEntry.getKey(), clusterEntry.getKey(), connEntry.getKey());
					} catch (Exception e) {
						throw new LifecycleException(new RuntimeException("Failed to start connector: " + connEntry.getKey(), e));
					}
				}

				// Start consumers and bind to workflows
				ContextDef context = runtime.getContext();
				if (context.routes() != null) {
					for (RouteDef routeDef : context.routes()) {
						String consumerKey = routeDef.uuid() + ":" + routeDef.from();
						IConsumer consumer = runtime.getConsumers().get(consumerKey);
						IWorkflow workflow = runtime.getRouteWorkflows().get(routeDef.uuid());

						if (consumer != null && workflow != null) {
							SubscriptionDef fromSub = runtime.getSubscriptions().get(routeDef.from());
							DataflowDef fromDf = runtime.getDataflows().get(fromSub.dataflow());

							Thread consumerThread = new Thread(() -> {
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
							}, "consumer-" + routeDef.uuid());
							consumerThread.setDaemon(true);
							consumerThread.start();
							consumerThreads.add(consumerThread);

							log.info("[{}][{}][{}] Route {} consumer started", assetId,
									tenantEntry.getKey(), clusterEntry.getKey(), routeDef.uuid());
						}
					}
				}
			}
		}

		log.info("==== GARGANTTUA EVENTS STARTED ====");
		return this;
	}

	@Override
	protected ILifecycle doStop() throws LifecycleException {
		log.info("==== STOPPING GARGANTTUA EVENTS ====");

		// Stop consumers
		for (Map.Entry<String, Map<String, ClusterRuntime>> tenantEntry : runtimes.entrySet()) {
			for (Map.Entry<String, ClusterRuntime> clusterEntry : tenantEntry.getValue().entrySet()) {
				ClusterRuntime runtime = clusterEntry.getValue();
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

	@Override
	protected ILifecycle doFlush() throws LifecycleException {
		runtimes.clear();
		consumerThreads.clear();
		return this;
	}
}
