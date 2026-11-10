package com.garganttua.events.api.context;

/**
 * Where a route sends a message it failed to process, and how many times it tries first.
 *
 * @param to       the error subscription
 * @param cast     the cast applied to the failed exchange
 * @param label    the label of the failure
 * @param attempts how many times the route runs a message before handing it to {@code to} — {@code 1}, the
 *                 default, hands it over on the first failure; more retry transient failures (a database
 *                 unreachable a moment) in place, waiting longer between each
 */
public record RouteExceptionsDef(
		String to,
		String cast,
		String label,
		int attempts) {

	/** The fewest attempts a route makes: one, and a failure goes to the error subscription at once. */
	public static final int SINGLE_ATTEMPT = 1;

	/** One attempt: a failure goes to the error subscription at once. */
	public RouteExceptionsDef(String to, String cast, String label) {
		this(to, cast, label, SINGLE_ATTEMPT);
	}

	public RouteExceptionsDef {
		if (attempts < SINGLE_ATTEMPT) {
			throw new IllegalArgumentException("A route makes at least one attempt, got " + attempts);
		}
	}
}
