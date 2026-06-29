package com.garganttua.events.api.context;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Immutable definition of a route: an inbound subscription ({@code from}), a list of output
 * subscriptions ({@code to}) the processed exchange is broadcast to, the business stages, and the
 * optional exception / synchronization policies.
 *
 * <p>{@code to} is a list so a route can <em>fan out</em> to several destinations
 * ({@code route(...).to(a).to(b)}); a single {@code .to(x)} yields a one-element list. The list is
 * never {@code null} (the canonical constructor normalises a {@code null} to an empty list), and the
 * field tolerates both JSON shapes via {@link StringOrListDeserializer}: a legacy {@code "to":"sub"}
 * and a multi-destination {@code "to":["a","b"]} both deserialize correctly.</p>
 */
// EI_EXPOSE: immutable route-definition record — the stages/to lists are supplied at build
// time and never mutated through the accessor; a defensive copy would change no
// observable behaviour.
@SuppressFBWarnings("EI_EXPOSE_REP")
public record RouteDef(
		String uuid,
		String from,
		@JsonDeserialize(using = StringOrListDeserializer.class) List<String> to,
		List<RouteStageDef> stages,
		RouteExceptionsDef exceptions,
		RouteSyncDef synchronization) {

	/**
	 * Canonical constructor normalising the {@code to} list: a {@code null} becomes an empty list so
	 * every accessor caller can iterate without a null guard.
	 *
	 * @param uuid            the route identifier
	 * @param from            the inbound subscription id
	 * @param to              the output subscription ids (fan-out targets); {@code null} → empty
	 * @param stages          the business stages
	 * @param exceptions      the optional exception-routing policy
	 * @param synchronization the optional synchronization policy
	 */
	public RouteDef {
		to = to == null ? new ArrayList<>() : new ArrayList<>(to);
	}
}
