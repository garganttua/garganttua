package com.garganttua.events.api.context;

import java.util.List;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

// EI_EXPOSE: immutable context-definition record — the lists are supplied already
// wrapped via Collections.unmodifiableList by the builder, so re-copying at the record
// level would be redundant and changes no observable behaviour.
@SuppressFBWarnings("EI_EXPOSE_REP")
public record ContextDef(
		String tenantId,
		String clusterId,
		List<TopicDef> topics,
		List<DataflowDef> dataflows,
		List<ConnectorDef> connectors,
		List<SubscriptionDef> subscriptions,
		List<RouteDef> routes,
		List<LockDef> locks) {}
