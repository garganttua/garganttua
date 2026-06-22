package com.garganttua.events.api;

public record ConnectorContext(
		String assetId,
		String tenantId,
		String clusterId) {}
