package com.garganttua.events.api;

import com.garganttua.core.lifecycle.ILifecycle;

/**
 * Engine contract for the Garganttua events runtime.
 *
 * <p>
 * Beyond the {@link ILifecycle} hooks, {@code IEvents} exposes the engine asset
 * identity and human-readable descriptions of its configured routes, joining each
 * route to the topics, connectors and dataflows referenced by its subscriptions.
 * </p>
 */
public interface IEvents extends ILifecycle {

	/**
	 * Returns the asset identifier this engine instance serves.
	 *
	 * @return the asset id supplied at build time
	 */
	String getAssetId();

	/**
	 * Renders a single route, identified by its UUID, as a human-readable multi-line
	 * description.
	 *
	 * <p>
	 * The route is searched across every configured cluster context. The rendering
	 * resolves the route's {@code from}/{@code to} subscriptions to their topic,
	 * connector ({@code type:version}) and dataflow within the same context, and lists
	 * the route stages together with the presence of synchronization and exception
	 * handling. Unknown references are rendered as {@code <unresolved: ref>} rather
	 * than failing.
	 * </p>
	 *
	 * @param routeUuid the UUID of the route to describe
	 * @return the rendered route description, or a clear not-found message when no
	 *         route with that UUID exists in any context
	 */
	String describeRoute(String routeUuid);

	/**
	 * Renders every route in every configured cluster context as a single
	 * human-readable, multi-line description.
	 *
	 * @return the rendered description of all routes, or a clear message when no route
	 *         is configured
	 */
	String describeRoutes();
}
