package com.garganttua.events.core;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.garganttua.core.dsl.DslException;
import com.garganttua.core.dsl.IObservableBuilder;
import com.garganttua.core.lifecycle.LifecycleException;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.workflow.IWorkflow;
import com.garganttua.core.workflow.dsl.IWorkflowBuilder;
import com.garganttua.core.workflow.dsl.WorkflowsBuilder;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.OutboundTarget;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.RouteDef;
import com.garganttua.events.api.context.RouteStageDef;
import com.garganttua.events.api.context.ConsumerConfigurationDef;
import com.garganttua.events.api.context.SubscriptionDef;
import com.garganttua.events.api.enums.PublicationMode;

/**
 * Compiles a {@link RouteDef} into an {@link IWorkflow}: binds the inbound/outbound workflow
 * variables, resolves each {@code to} subscription into an {@link OutboundTarget} (creating its
 * producer, wrapping it for {@code TIME_INTERVAL} publication when configured), and appends the
 * engine-owned transport stages ({@code protocol_in} when the inbound dataflow is encapsulated, then
 * the declared business stages, then a single {@code produce(@exchange, @outbounds, ...)} broadcast).
 *
 * <p>Extracted from {@code Events} as a cohesive route-to-workflow compilation collaborator so the
 * engine class stays under the file-size gate. It is stateless beyond the shared, engine-owned
 * configuration it is constructed with (the asset id, the two builder dependencies and the shared
 * {@code TIME_INTERVAL} producer registry it appends to).</p>
 */
final class RouteWorkflowCompiler {

	private static final Logger log = Logger.getLogger(RouteWorkflowCompiler.class);

	private final String assetId;
	private final IObservableBuilder<?, ?> injectionContextBuilder;
	private final IObservableBuilder<?, ?> scriptsBuilder;
	private final List<TimeIntervalProducer> timeIntervalProducers;

	RouteWorkflowCompiler(String assetId, IObservableBuilder<?, ?> injectionContextBuilder,
			IObservableBuilder<?, ?> scriptsBuilder, List<TimeIntervalProducer> timeIntervalProducers) {
		this.assetId = assetId;
		this.injectionContextBuilder = injectionContextBuilder;
		this.scriptsBuilder = scriptsBuilder;
		this.timeIntervalProducers = timeIntervalProducers;
	}

	/**
	 * Compiles the given route into a workflow within its cluster runtime, registering each created
	 * outbound producer in the runtime.
	 *
	 * @param routeDef  the route to compile
	 * @param runtime   the cluster runtime holding the resolved subscriptions/connectors/dataflows
	 * @param tenantId  the tenant id
	 * @param clusterId the cluster id
	 * @return the compiled route workflow
	 * @throws LifecycleException when the inbound subscription is missing or the workflow fails to build
	 */
	IWorkflow compile(RouteDef routeDef, ClusterRuntime runtime, String tenantId, String clusterId)
			throws LifecycleException {
		try {
			SubscriptionDef fromSub = runtime.getSubscriptions().get(routeDef.from());
			if (fromSub == null) {
				throw new LifecycleException("From subscription not found: " + routeDef.from());
			}

			IWorkflowBuilder wb = WorkflowsBuilder.builder()
					.provide(injectionContextBuilder)
					.provide(scriptsBuilder)
					.workflow("route-" + routeDef.uuid())
					.variable("assetId", assetId)
					.variable("tenantId", tenantId)
					.variable("clusterId", clusterId)
					.variable("subscriptionId", fromSub.id())
					.inlineAll();

			DataflowDef fromDf = bindInbound(wb, runtime, fromSub);
			boolean hasProducer = bindOutbound(routeDef, runtime, wb);

			addStages(routeDef, wb, fromSub, isEncapsulated(fromDf), hasProducer);

			return wb.build();
		} catch (DslException e) {
			throw new LifecycleException(
					new RuntimeException("Failed to build route workflow: " + routeDef.uuid(), e));
		}
	}

	/** Binds the inbound (from) workflow variables and returns the inbound dataflow (or {@code null}). */
	private DataflowDef bindInbound(IWorkflowBuilder wb, ClusterRuntime runtime, SubscriptionDef fromSub) {
		DataflowDef fromDf = runtime.getDataflows().get(fromSub.dataflow());
		if (fromDf != null) {
			wb.variable("version", fromDf.version());
			wb.variable("dataflowUuid", fromDf.uuid());
		}
		wb.variable("connectorName", fromSub.connector());
		return fromDf;
	}

	/**
	 * Resolves each {@code routeDef.to()} subscription into an {@link OutboundTarget} (creating its
	 * producer) and binds the whole list as the {@code @outbounds} workflow variable the auto-injected
	 * {@code produce} stage broadcasts to. An unresolvable destination is skipped with a warning.
	 *
	 * <p>For backward compatibility with any external script referencing them, the first resolved
	 * target's metadata is also bound to the legacy {@code @producer} / {@code @topicRef} / ...
	 * variables; the engine's own produce stage uses {@code @outbounds}.</p>
	 *
	 * @return whether at least one outbound target was bound
	 */
	private boolean bindOutbound(RouteDef routeDef, ClusterRuntime runtime, IWorkflowBuilder wb) {
		List<OutboundTarget> outbounds = new ArrayList<>();
		for (String toRef : routeDef.to()) {
			resolveOutboundTarget(routeDef, runtime, toRef).ifPresent(outbounds::add);
		}
		wb.variable("outbounds", outbounds);
		if (!outbounds.isEmpty()) {
			bindLegacyOutboundVariables(wb, outbounds.get(0));
		}
		return !outbounds.isEmpty();
	}

	/**
	 * Resolves one {@code to} subscription into an {@link OutboundTarget}, creating (and registering)
	 * its producer; returns empty when the subscription, its connector or its dataflow is missing.
	 */
	private Optional<OutboundTarget> resolveOutboundTarget(RouteDef routeDef, ClusterRuntime runtime,
			String toRef) {
		SubscriptionDef toSub = runtime.getSubscriptions().get(toRef);
		if (toSub == null) {
			log.warn("Route {}: outbound subscription '{}' not found; destination skipped",
					routeDef.uuid(), toRef);
			return Optional.empty();
		}
		IConnector toConnector = runtime.getConnectors().get(toSub.connector());
		DataflowDef toDf = runtime.getDataflows().get(toSub.dataflow());
		if (toConnector == null || toDf == null) {
			log.warn("Route {}: outbound subscription '{}' has no resolvable connector/dataflow; "
					+ "destination skipped", routeDef.uuid(), toSub.id());
			return Optional.empty();
		}
		IProducer producer = wrapForPublicationMode(routeDef, toSub, toConnector.createProducer(toSub, toDf));
		runtime.getProducers().put(routeDef.uuid() + ":" + toSub.id(), producer);
		String destinationPolicy = toSub.producerConfiguration() != null
				&& toSub.producerConfiguration().destinationPolicy() != null
						? toSub.producerConfiguration().destinationPolicy().name()
						: null;
		return Optional.of(new OutboundTarget(producer, toDf.encapsulated(), toSub.topic(),
				toDf.version(), toDf.uuid(), toSub.connector(), toSub.id(), destinationPolicy));
	}

	/** Binds the legacy single-target {@code @producer}/{@code @topicRef}/... variables to one target. */
	private void bindLegacyOutboundVariables(IWorkflowBuilder wb, OutboundTarget target) {
		wb.variable("producer", target.producer());
		wb.variable("topicRef", target.topicRef());
		wb.variable("outVersion", target.version());
		wb.variable("outDataflowUuid", target.dataflowUuid());
		wb.variable("outConnectorName", target.connectorName());
		wb.variable("outSubscriptionId", target.subscriptionId());
	}

	private static boolean isEncapsulated(DataflowDef df) {
		return df != null && df.encapsulated();
	}

	/**
	 * Wraps the connector producer in a {@link TimeIntervalProducer} when the outbound subscription
	 * publishes on a {@code TIME_INTERVAL}; otherwise returns it unchanged ({@code ON_CHANGE} =
	 * immediate publish, the default).
	 */
	private IProducer wrapForPublicationMode(RouteDef routeDef, SubscriptionDef toSub, IProducer producer) {
		if (toSub.publicationMode() != PublicationMode.TIME_INTERVAL || toSub.timeInterval() == null) {
			return producer;
		}
		TimeIntervalProducer.PersistentBuffer buffer = null;
		if (toSub.buffered() && toSub.bufferPersisted()) {
			try {
				buffer = new TimeIntervalProducer.PersistentBuffer(bufferFile(routeDef, toSub));
			} catch (IOException e) {
				log.warn("Route {}: cannot open persistent buffer for subscription {}; using in-memory",
						routeDef.uuid(), toSub.id(), e);
			}
		}
		TimeIntervalProducer timed = new TimeIntervalProducer(producer, toSub.timeInterval(),
				toSub.buffered(), buffer);
		timeIntervalProducers.add(timed);
		log.info("[{}] Route {}: subscription {} → TIME_INTERVAL (buffered={}, persisted={})",
				assetId, routeDef.uuid(), toSub.id(), toSub.buffered(), buffer != null);
		return timed;
	}

	private Path bufferFile(RouteDef routeDef, SubscriptionDef toSub) {
		return Paths.get(System.getProperty("java.io.tmpdir"), "garganttua-events-buffer",
				assetId, routeDef.uuid(), toSub.id() + ".buf");
	}

	/**
	 * Compiles the route stages into the workflow: {@code protocol_in} first when the inbound dataflow
	 * is encapsulated, then the declared business stages, then a single {@code produce(@exchange,
	 * @outbounds, ...)} stage broadcasting the processed exchange to every outbound target (each
	 * encapsulated destination is envelope-wrapped on the fly). The route author declares only business
	 * logic; envelope (de)serialisation and emission to one or many destinations are automatic.
	 */
	private void addStages(RouteDef routeDef, IWorkflowBuilder wb, SubscriptionDef fromSub,
			boolean fromEncapsulated, boolean hasProducer) throws DslException {
		if (fromEncapsulated) {
			addExpressionStage(wb, "protocol_in",
					"protocol_in(@exchange, @assetId, @clusterId, @subscriptionId, @version)",
					null, null, null);
			// Inbound filter (legacy GGInFilterProcessor), opt-in via the subscription's consumer
			// config. Only on encapsulated inbound flows, where protocol_in has set the dataflow
			// version that filter_in checks; a non-encapsulated flow has no version to filter on.
			ConsumerConfigurationDef consumer = fromSub.consumerConfiguration();
			if (consumer != null) {
				String destination = consumer.destinationPolicy() != null
						? consumer.destinationPolicy().name() : "TO_ANY";
				String origin = consumer.originPolicy() != null
						? consumer.originPolicy().name() : "FROM_ANY";
				addExpressionStage(wb, "filter_in",
						"filter_in(@exchange, \"" + destination + "\", \"" + origin
								+ "\", @assetId, @clusterId, @version)",
						null, null, null);
			}
		}
		if (routeDef.stages() != null) {
			for (RouteStageDef stageDef : routeDef.stages()) {
				addExpressionStage(wb, stageDef.name(), stageDef.expression(), stageDef.condition(),
						stageDef.catchExpression(), stageDef.catchDownstreamExpression());
			}
		}
		if (hasProducer) {
			addExpressionStage(wb, "produce",
					"produce(@exchange, @outbounds, @assetId, @clusterId)", null, null, null);
		}
	}

	/** Adds one inline stage {@code exchange <- expression} with optional condition / catch handlers. */
	private void addExpressionStage(IWorkflowBuilder wb, String name, String expression,
			String condition, String catchExpression, String catchDownstreamExpression) throws DslException {
		var stageBuilder = wb.stage(name);
		var scriptBuilder = stageBuilder.script("exchange <- " + expression).name(name).inline();
		if (condition != null) {
			scriptBuilder.when(condition);
		}
		if (catchExpression != null) {
			scriptBuilder.catch_(catchExpression);
		}
		if (catchDownstreamExpression != null) {
			scriptBuilder.catchDownstream(catchDownstreamExpression);
		}
		scriptBuilder.up(); // → stage
		stageBuilder.up();  // → workflow
	}
}
