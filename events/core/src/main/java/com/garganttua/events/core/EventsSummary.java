package com.garganttua.events.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.garganttua.events.api.context.ConnectorDef;
import com.garganttua.events.api.context.ContextDef;
import com.garganttua.events.api.context.RouteDef;
import com.garganttua.events.api.context.RouteStageDef;
import com.garganttua.events.api.context.SubscriptionDef;

/**
 * Builds the {@code IBootstrapSummaryContributor} item map for the events engine: the asset id and
 * cluster count, then — mirroring {@code ApiSummary}'s per-domain detail — a per-route and
 * per-connector breakdown (each route's {@code from → to} endpoints and stage names, each
 * connector's {@code type:version}) alongside the topic / dataflow / subscription totals.
 *
 * <p>Extracted from {@code Events} as a cohesive, side-effect-free aggregation collaborator so the
 * engine class stays under the file-size gate.</p>
 *
 * @since 3.0.0-ALPHA04
 */
final class EventsSummary {

	private EventsSummary() {
		// utility holder — no instances
	}

	/**
	 * Builds the ordered summary items for the given asset and its contexts: aggregate counts plus a
	 * human-readable, per-route / per-connector topology breakdown.
	 *
	 * @param assetId  the engine asset identifier
	 * @param contexts the configured cluster contexts
	 * @return an ordered, human-readable map of summary metrics
	 */
	static Map<String, String> items(String assetId, List<ContextDef> contexts) {
		Map<String, String> items = new LinkedHashMap<>();
		items.put("Asset", assetId);
		items.put("Clusters", String.valueOf(contexts.size()));

		items.put("Routes", String.valueOf(count(contexts, ContextDef::routes)));
		appendRoutes(items, contexts);

		items.put("Connectors", String.valueOf(count(contexts, ContextDef::connectors)));
		appendConnectors(items, contexts);

		items.put("Topics", String.valueOf(count(contexts, ContextDef::topics)));
		items.put("Dataflows", String.valueOf(count(contexts, ContextDef::dataflows)));
		items.put("Subscriptions", String.valueOf(count(contexts, ContextDef::subscriptions)));
		return items;
	}

	/** Appends one detail line per route ({@code from → to}) plus an indented stage list. */
	private static void appendRoutes(Map<String, String> items, List<ContextDef> contexts) {
		for (ContextDef context : contexts) {
			if (context.routes() == null) {
				continue;
			}
			for (RouteDef route : context.routes()) {
				items.put("  Route '" + route.uuid() + "'",
						endpoint(context, route.from()) + " → " + endpoints(context, route.to()));
				if (route.stages() != null && !route.stages().isEmpty()) {
					items.put("    stages", route.stages().stream()
							.map(RouteStageDef::name)
							.collect(Collectors.joining(", ")));
				}
			}
		}
	}

	/** Appends one detail line per connector ({@code type:version}). */
	private static void appendConnectors(Map<String, String> items, List<ContextDef> contexts) {
		for (ContextDef context : contexts) {
			if (context.connectors() == null) {
				continue;
			}
			for (ConnectorDef connector : context.connectors()) {
				items.put("  Connector '" + connector.name() + "'",
						connector.type() + ":" + connector.version());
			}
		}
	}

	/** Renders the route's destination subscription(s), comma-joined, or {@code —} when empty. */
	private static String endpoints(ContextDef context, List<String> subscriptionIds) {
		if (subscriptionIds == null || subscriptionIds.isEmpty()) {
			return "—";
		}
		return subscriptionIds.stream()
				.map(id -> endpoint(context, id))
				.collect(Collectors.joining(", "));
	}

	/** Renders a route endpoint subscription as {@code topic (connector)}, or the raw id as a fallback. */
	private static String endpoint(ContextDef context, String subscriptionId) {
		if (subscriptionId == null) {
			return "—";
		}
		if (context.subscriptions() != null) {
			for (SubscriptionDef sub : context.subscriptions()) {
				if (subscriptionId.equals(sub.id())) {
					return sub.topic() + " (" + sub.connector() + ")";
				}
			}
		}
		return subscriptionId;
	}

	/** Sums the size of the selected list across all contexts, treating null as empty. */
	private static int count(List<ContextDef> contexts, Function<ContextDef, List<?>> selector) {
		int total = 0;
		for (ContextDef context : contexts) {
			List<?> list = selector.apply(context);
			total += list == null ? 0 : list.size();
		}
		return total;
	}
}
