package com.garganttua.events.api.context;

import java.util.Map;

public record ConnectorDef(
		String name,
		String type,
		String version,
		Map<String, String> configuration) {}
