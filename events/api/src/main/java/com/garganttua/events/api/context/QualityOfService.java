package com.garganttua.events.api.context;

/**
 * When a subscription's consumer acknowledges a message to its transport — declared per subscription
 * ({@code consumerConfiguration().qualityOfService(...)}, {@code "qualityOfService"} in a JSON context).
 *
 * <p>
 * Not declaring it keeps each connector's own behaviour: Kafka and BigQueue acknowledge on receipt, a
 * connector that acknowledges after the route (an {@link com.garganttua.events.api.IAcknowledgingConsumer})
 * keeps doing so.
 * </p>
 *
 * @since 3.0.0-ALPHA34
 */
public enum QualityOfService {

	/**
	 * Acknowledged on receipt: a message whose route fails is lost. The cheapest — nothing waits for the route.
	 */
	AT_MOST_ONCE,

	/**
	 * Acknowledged once the message is SAFE — its route succeeded, or it was handed to the route's error
	 * subscription; otherwise the transport delivers it again. A message may then be processed twice: its
	 * consumers must be idempotent. The engine refuses to start a route declaring it on a connector that
	 * cannot acknowledge after the route.
	 */
	AT_LEAST_ONCE
}
