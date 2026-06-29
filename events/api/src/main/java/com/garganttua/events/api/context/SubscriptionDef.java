package com.garganttua.events.api.context;

import com.garganttua.events.api.enums.PublicationMode;

/**
 * A subscription on a topic, either consumed (a route's {@code from}) or produced to (a route's
 * {@code to}).
 *
 * @param buffered        for a {@code TIME_INTERVAL} producer: when {@code true}, every exchange of
 *                        the interval is buffered and flushed as a batch on each tick; when
 *                        {@code false}, only the latest exchange is published (last-wins sampling).
 * @param bufferPersisted when {@code true} (and {@code buffered}), the buffer is file-backed and
 *                        survives a restart; when {@code false}, the buffer is in-memory.
 */
public record SubscriptionDef(
		String id,
		String dataflow,
		String topic,
		String connector,
		PublicationMode publicationMode,
		ConsumerConfigurationDef consumerConfiguration,
		ProducerConfigurationDef producerConfiguration,
		TimeIntervalDef timeInterval,
		boolean buffered,
		boolean bufferPersisted) {

	/** Backward-compatible constructor: no buffering ({@code buffered = bufferPersisted = false}). */
	public SubscriptionDef(String id, String dataflow, String topic, String connector,
			PublicationMode publicationMode, ConsumerConfigurationDef consumerConfiguration,
			ProducerConfigurationDef producerConfiguration, TimeIntervalDef timeInterval) {
		this(id, dataflow, topic, connector, publicationMode, consumerConfiguration,
				producerConfiguration, timeInterval, false, false);
	}
}
