package com.garganttua.events.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.garganttua.core.observability.Logger;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IReconfigurableConnector;
import com.garganttua.events.api.exceptions.EventsException;

/**
 * Connector reconfiguration collaborator for {@link Events}: finds a declared connector in the engine's live
 * {@link ClusterRuntime} map — in one context, or in every context that declares it — and hands it its new
 * configuration through {@link IReconfigurableConnector}.
 *
 * <p>Extracted from {@code Events} to keep that class under the code-size gate; it shares the engine's live runtime
 * map and is therefore not a value object.</p>
 */
final class EventsConnectorReconfigurer {

	private static final Logger log = Logger.getLogger(EventsConnectorReconfigurer.class);

	/** One context's instance of the connector being reconfigured. */
	private record Target(IConnector connector, String tenantId, String clusterId) {
	}

	private final Map<String, Map<String, ClusterRuntime>> runtimes;
	private final String assetId;

	EventsConnectorReconfigurer(Map<String, Map<String, ClusterRuntime>> runtimes, String assetId) {
		this.runtimes = runtimes;
		this.assetId = assetId;
	}

	/** See {@link com.garganttua.events.api.IEvents#reconfigureConnector(String, String, String, Map)}. */
	void reconfigure(String tenantId, String clusterId, String connectorName, Map<String, String> configuration)
			throws EventsException {
		Map<String, ClusterRuntime> clusters = this.runtimes.get(tenantId);
		ClusterRuntime runtime = clusters == null ? null : clusters.get(clusterId);
		if (runtime == null) {
			throw new EventsException("No context [" + tenantId + "][" + clusterId + "] in engine " + this.assetId
					+ " (is the engine initialised?)");
		}
		IConnector connector = runtime.getConnectors().get(connectorName);
		if (connector == null) {
			throw new EventsException("No connector '" + connectorName + "' in context [" + tenantId + "]["
					+ clusterId + "]");
		}
		apply(new Target(connector, tenantId, clusterId), connectorName, configuration);
	}

	/** See {@link com.garganttua.events.api.IEvents#reconfigureConnector(String, Map)}. */
	int reconfigureEverywhere(String connectorName, Map<String, String> configuration) throws EventsException {
		// Collect first, so that a connector that is not reconfigurable refuses the whole call before any change.
		List<Target> targets = new ArrayList<>();
		for (Map.Entry<String, Map<String, ClusterRuntime>> tenant : this.runtimes.entrySet()) {
			for (Map.Entry<String, ClusterRuntime> cluster : tenant.getValue().entrySet()) {
				IConnector connector = cluster.getValue().getConnectors().get(connectorName);
				if (connector != null) {
					Target target = new Target(connector, tenant.getKey(), cluster.getKey());
					requireReconfigurable(target, connectorName);
					targets.add(target);
				}
			}
		}
		if (targets.isEmpty()) {
			throw new EventsException("No connector '" + connectorName + "' in any context of engine " + this.assetId);
		}
		// A connector instance shared by several contexts (bean resolution) is reconfigured once.
		Set<IConnector> done = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Target target : targets) {
			if (done.add(target.connector())) {
				apply(target, connectorName, configuration);
			}
		}
		return done.size();
	}

	private static IReconfigurableConnector requireReconfigurable(Target target, String connectorName)
			throws EventsException {
		if (target.connector() instanceof IReconfigurableConnector reconfigurable) {
			return reconfigurable;
		}
		throw new EventsException("Connector '" + connectorName + "' in context [" + target.tenantId() + "]["
				+ target.clusterId() + "] is not reconfigurable (" + target.connector().getClass().getName() + ")");
	}

	private void apply(Target target, String connectorName, Map<String, String> configuration)
			throws EventsException {
		IReconfigurableConnector reconfigurable = requireReconfigurable(target, connectorName);
		if (configuration == null) {
			throw new EventsException("No configuration given for connector '" + connectorName + "'");
		}
		reconfigurable.reconfigure(configuration);
		log.info("[{}][{}][{}] Connector {} reconfigured", this.assetId, target.tenantId(), target.clusterId(),
				connectorName);
	}
}
