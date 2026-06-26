package com.garganttua.events.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.garganttua.events.api.context.ContextDef;

/**
 * Builds the {@code IBootstrapSummaryContributor} item map for the events engine: the asset id,
 * cluster count and the totals (summed across every {@link ContextDef}) of routes, connectors,
 * topics, dataflows and subscriptions.
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
	 * Builds the ordered summary items for the given asset and its contexts.
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
		items.put("Connectors", String.valueOf(count(contexts, ContextDef::connectors)));
		items.put("Topics", String.valueOf(count(contexts, ContextDef::topics)));
		items.put("Dataflows", String.valueOf(count(contexts, ContextDef::dataflows)));
		items.put("Subscriptions", String.valueOf(count(contexts, ContextDef::subscriptions)));
		return items;
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
