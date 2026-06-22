package com.garganttua.events.api.context;

import java.util.List;

public record RouteDef(
		String uuid,
		String from,
		String to,
		List<RouteStageDef> stages,
		RouteExceptionsDef exceptions,
		RouteSyncDef synchronization) {}
