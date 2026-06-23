package com.garganttua.events.api.context;

import java.util.List;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

// EI_EXPOSE: immutable route-definition record — the stages list is supplied at build
// time and never mutated through the accessor; a defensive copy would change no
// observable behaviour.
@SuppressFBWarnings("EI_EXPOSE_REP")
public record RouteDef(
		String uuid,
		String from,
		String to,
		List<RouteStageDef> stages,
		RouteExceptionsDef exceptions,
		RouteSyncDef synchronization) {}
