package com.garganttua.events.api.context;

public record RouteStageDef(
		String name,
		String expression,
		String condition,
		String catchExpression,
		String catchDownstreamExpression) {}
