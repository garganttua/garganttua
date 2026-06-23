package com.garganttua.events.api.context;

import java.util.Map;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

// EI_EXPOSE: immutable connector-definition record — the configuration map is config
// supplied at build time and never mutated through the accessor; a defensive copy would
// change no observable behaviour.
@SuppressFBWarnings("EI_EXPOSE_REP")
public record ConnectorDef(
		String name,
		String type,
		String version,
		Map<String, String> configuration) {}
