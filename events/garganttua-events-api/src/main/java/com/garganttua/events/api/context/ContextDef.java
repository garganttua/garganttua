package com.garganttua.events.api.context;

import java.util.List;

public record ContextDef(
		String tenantId,
		String clusterId,
		List<TopicDef> topics,
		List<DataflowDef> dataflows,
		List<ConnectorDef> connectors,
		List<SubscriptionDef> subscriptions,
		List<RouteDef> routes,
		List<LockDef> locks) {}
