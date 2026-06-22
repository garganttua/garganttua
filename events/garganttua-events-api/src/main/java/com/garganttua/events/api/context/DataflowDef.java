package com.garganttua.events.api.context;

public record DataflowDef(
		String uuid,
		String name,
		String type,
		boolean garanteeOrder,
		String version,
		boolean encapsulated) {}
