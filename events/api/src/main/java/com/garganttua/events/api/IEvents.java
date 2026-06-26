package com.garganttua.events.api;

import com.garganttua.core.lifecycle.ILifecycle;
import com.garganttua.core.observability.IObservable;
import com.garganttua.events.api.exceptions.EventsException;

/**
 * Engine contract for the Garganttua events runtime.
 *
 * <p>
 * Beyond the {@link ILifecycle} hooks, {@code IEvents} exposes the engine asset
 * identity and human-readable descriptions of its configured routes, joining each
 * route to the topics, connectors and dataflows referenced by its subscriptions.
 * </p>
 *
 * <p>
 * The engine is also {@link IObservable}: callers can attach observers to receive the
 * {@code events:route:*} observability events the engine emits around each routed message,
 * mirroring how garganttua-api's {@code IDomain} exposes {@code api:operation:*} events.
 * </p>
 */
public interface IEvents extends ILifecycle, IObservable {

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

	/**
	 * Publishes a raw payload onto the given topic, resolved against the running topology's
	 * subscriptions.
	 *
	 * <p>The first subscription whose topic equals {@code topic} is selected, and the payload is
	 * pushed through the engine's own connector instance for that subscription (no duplicate
	 * connector is created). Thread-safe: callable from arbitrary application threads; the producer
	 * backing the resolved subscription is memoised, and per-message thread-safety delegates to the
	 * connector's producer.</p>
	 *
	 * @param topic   the topic string to publish onto; must match a subscription topic
	 * @param payload the raw message bytes
	 * @throws EventsException when no subscription declares that topic, or publication fails
	 */
	void publish(String topic, byte[] payload) throws EventsException;

	/**
	 * Returns a reusable producer for the subscription with the given id.
	 *
	 * <p>The producer is backed by the engine's own connector instance for that subscription and is
	 * memoised, so repeated calls for the same subscription id return the same producer. Thread-safe:
	 * callable from arbitrary application threads.</p>
	 *
	 * @param subscriptionId the id of the subscription whose producer is requested
	 * @return a reusable producer for that subscription, never {@code null}
	 * @throws EventsException when no subscription has that id, or the producer cannot be created
	 */
	IProducer producer(String subscriptionId) throws EventsException;
}
