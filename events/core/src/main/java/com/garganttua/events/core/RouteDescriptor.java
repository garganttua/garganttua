package com.garganttua.events.core;

import java.util.List;
import java.util.Optional;

import com.garganttua.events.api.context.ConnectorDef;
import com.garganttua.events.api.context.ContextDef;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.RouteDef;
import com.garganttua.events.api.context.RouteStageDef;
import com.garganttua.events.api.context.SubscriptionDef;
import com.garganttua.events.api.context.TopicDef;

/**
 * Stateless renderer that turns route configuration ({@link RouteDef} within its
 * {@link ContextDef}) into human-readable, multi-line text.
 *
 * <p>
 * A route is joined to the topic, connector ({@code type:version}) and dataflow
 * referenced by its {@code from}/{@code to} subscriptions, all resolved against the
 * lists of the <em>same</em> context. References that do not resolve are rendered as
 * {@code <unresolved: ref>} so a partial configuration still produces useful output
 * instead of throwing.
 * </p>
 *
 * <p>
 * The {@code describe*} entry points operate on a snapshot of the engine's context
 * list; this class holds no mutable state and is safe to use from any thread.
 * </p>
 */
final class RouteDescriptor {

	/** Rendered placeholder for an absent (null) reference. */
	private static final String NONE = "none";

	private RouteDescriptor() {
	}

	/**
	 * Renders the route with the given UUID, searched across all contexts.
	 *
	 * @param contexts  the configured cluster contexts
	 * @param routeUuid the UUID of the route to render
	 * @return the rendered route, or a not-found message when absent
	 */
	static String describeRoute(List<ContextDef> contexts, String routeUuid) {
		for (ContextDef context : contexts) {
			if (context.routes() == null) {
				continue;
			}
			for (RouteDef route : context.routes()) {
				if (route.uuid() != null && route.uuid().equals(routeUuid)) {
					return renderRoute(context, route);
				}
			}
		}
		return "Route not found: " + routeUuid;
	}

	/**
	 * Renders every route across every context.
	 *
	 * @param contexts the configured cluster contexts
	 * @return the rendered routes, or a message when no route is configured
	 */
	static String describeRoutes(List<ContextDef> contexts) {
		StringBuilder sb = new StringBuilder();
		for (ContextDef context : contexts) {
			if (context.routes() == null) {
				continue;
			}
			for (RouteDef route : context.routes()) {
				if (sb.length() > 0) {
					sb.append(System.lineSeparator());
				}
				sb.append(renderRoute(context, route));
			}
		}
		return sb.length() == 0 ? "No routes configured" : sb.toString();
	}

	private static String renderRoute(ContextDef context, RouteDef route) {
		String nl = System.lineSeparator();
		StringBuilder sb = new StringBuilder();
		sb.append("Route ").append(route.uuid())
				.append(" [tenant=").append(context.tenantId())
				.append(", cluster=").append(context.clusterId()).append(']').append(nl);
		sb.append("  from: ").append(renderEndpoint(context, route.from())).append(nl);
		sb.append("  to:   ").append(renderEndpoint(context, route.to())).append(nl);
		sb.append("  stages: ").append(renderStages(route)).append(nl);
		sb.append("  sync: ").append(route.synchronization() != null ? "configured" : NONE)
				.append(" | exceptions: ").append(route.exceptions() != null ? "configured" : NONE);
		return sb.toString();
	}

	private static String renderEndpoint(ContextDef context, String subscriptionId) {
		if (subscriptionId == null) {
			return NONE;
		}
		Optional<SubscriptionDef> sub = findSubscription(context, subscriptionId);
		if (sub.isEmpty()) {
			return "sub '" + subscriptionId + "' " + unresolved(subscriptionId);
		}
		SubscriptionDef s = sub.get();
		return "sub '" + s.id() + "' -> topic " + renderTopic(context, s.topic())
				+ ", connector " + renderConnector(context, s.connector())
				+ ", dataflow " + renderDataflow(context, s.dataflow());
	}

	private static String renderTopic(ContextDef context, String topicRef) {
		if (topicRef == null) {
			return NONE;
		}
		if (context.topics() != null) {
			for (TopicDef topic : context.topics()) {
				if (topicRef.equals(topic.ref())) {
					return "'" + topic.ref() + "'";
				}
			}
		}
		return "'" + topicRef + "' " + unresolved(topicRef);
	}

	private static String renderConnector(ContextDef context, String connectorName) {
		if (connectorName == null) {
			return NONE;
		}
		if (context.connectors() != null) {
			for (ConnectorDef connector : context.connectors()) {
				if (connectorName.equals(connector.name())) {
					return "'" + connector.name() + "' ("
							+ connector.type() + ":" + connector.version() + ")";
				}
			}
		}
		return "'" + connectorName + "' " + unresolved(connectorName);
	}

	private static String renderDataflow(ContextDef context, String dataflowRef) {
		if (dataflowRef == null) {
			return NONE;
		}
		Optional<DataflowDef> df = findDataflow(context, dataflowRef);
		if (df.isEmpty()) {
			return "'" + dataflowRef + "' " + unresolved(dataflowRef);
		}
		DataflowDef d = df.get();
		return "'" + d.name() + "' (" + d.type() + " v" + d.version()
				+ ", order=" + d.garanteeOrder() + ", encap=" + d.encapsulated() + ")";
	}

	private static String renderStages(RouteDef route) {
		if (route.stages() == null || route.stages().isEmpty()) {
			return "[]";
		}
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < route.stages().size(); i++) {
			RouteStageDef stage = route.stages().get(i);
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(stage.name()).append(": ").append(stage.expression());
		}
		return sb.append(']').toString();
	}

	private static Optional<SubscriptionDef> findSubscription(ContextDef context, String id) {
		if (context.subscriptions() == null) {
			return Optional.empty();
		}
		return context.subscriptions().stream()
				.filter(s -> id.equals(s.id())).findFirst();
	}

	/**
	 * Resolves a dataflow reference, matching a subscription's {@code dataflow} against a
	 * {@link DataflowDef} by UUID first (the engine keys dataflows by UUID), then by name.
	 */
	private static Optional<DataflowDef> findDataflow(ContextDef context, String ref) {
		if (context.dataflows() == null) {
			return Optional.empty();
		}
		Optional<DataflowDef> byUuid = context.dataflows().stream()
				.filter(d -> ref.equals(d.uuid())).findFirst();
		if (byUuid.isPresent()) {
			return byUuid;
		}
		return context.dataflows().stream()
				.filter(d -> ref.equals(d.name())).findFirst();
	}

	private static String unresolved(String ref) {
		return "<unresolved: " + ref + ">";
	}
}
