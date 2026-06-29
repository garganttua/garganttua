package com.garganttua.events.api;

/**
 * One resolved outbound destination of a route: the {@link IProducer} that emits to it, whether its
 * dataflow is encapsulated (so the exchange must be wrapped in a {@code Message} envelope before
 * publishing), and the per-target protocol metadata used to stamp that envelope.
 *
 * <p>A route with multiple {@code to} subscriptions resolves into one {@code OutboundTarget} per
 * destination; the engine's {@code produce} stage encapsulates (when needed) and publishes to every
 * target, broadcasting the processed exchange.</p>
 *
 * @param producer       the producer that publishes to this destination
 * @param encapsulated   whether the destination dataflow is encapsulated (envelope-wrapped)
 * @param topicRef       the destination topic reference
 * @param version        the destination dataflow version
 * @param dataflowUuid   the destination dataflow UUID
 * @param connectorName  the destination connector name
 * @param subscriptionId the destination subscription id
 * @param destinationPolicy the outbound {@code DestinationPolicy} name (from the destination's
 *                       producer config) used to normalise the exchange address before publishing —
 *                       {@code TO_ANY} broadcasts (clears {@code toUuid}), {@code ONLY_TO_*} keeps it;
 *                       {@code null} means no normalisation (legacy GGOutFilterProcessor behaviour)
 */
public record OutboundTarget(
		IProducer producer,
		boolean encapsulated,
		String topicRef,
		String version,
		String dataflowUuid,
		String connectorName,
		String subscriptionId,
		String destinationPolicy) {

	/** Backward-compatible constructor: no outbound destination policy (no filter_out normalisation). */
	public OutboundTarget(IProducer producer, boolean encapsulated, String topicRef, String version,
			String dataflowUuid, String connectorName, String subscriptionId) {
		this(producer, encapsulated, topicRef, version, dataflowUuid, connectorName, subscriptionId, null);
	}
}
