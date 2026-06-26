package com.garganttua.events.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.garganttua.core.observability.Logger;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.SubscriptionDef;
import com.garganttua.events.api.exceptions.ConnectorException;
import com.garganttua.events.api.exceptions.EventsException;

/**
 * Programmatic publication collaborator for {@link Events}: resolves a topology subscription (by
 * topic or by id) against the engine's live {@link ClusterRuntime} map and publishes raw payloads
 * through the engine's <em>own</em> connector instances — never a freshly instantiated duplicate.
 *
 * <p>Ad-hoc producers created on the publish/producer paths are memoised in a thread-safe
 * {@link ConcurrentHashMap} keyed by subscription id, so repeated publications reuse a single
 * producer. This collaborator is extracted from {@code Events} purely to keep that class under the
 * code-size gate; it shares the engine's live runtime map and is therefore not a value object.</p>
 */
final class EventsPublisher {

	private static final Logger log = Logger.getLogger(EventsPublisher.class);

	/** Unchecked carrier so {@code computeIfAbsent} can surface a checked creation failure. */
	private static final class ProducerCreationException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		ProducerCreationException(String message) {
			super(message);
		}
	}

	/** A resolved (runtime, subscription) pair. */
	private record Resolved(ClusterRuntime runtime, SubscriptionDef subscription) {
	}

	private final Map<String, Map<String, ClusterRuntime>> runtimes;
	private final Map<String, IProducer> publishProducers = new ConcurrentHashMap<>();

	EventsPublisher(Map<String, Map<String, ClusterRuntime>> runtimes) {
		this.runtimes = runtimes;
	}

	/**
	 * Publishes a raw payload onto the first subscription whose topic matches.
	 *
	 * @param topic   the topic to publish onto
	 * @param payload the raw message bytes
	 * @throws EventsException when no subscription declares that topic, or publication fails
	 */
	void publish(String topic, byte[] payload) throws EventsException {
		Resolved resolved = resolveByTopic(topic);
		IProducer producer = producerFor(resolved.runtime(), resolved.subscription());
		try {
			producer.publish(payload);
		} catch (ConnectorException e) {
			throw new EventsException("Failed to publish onto topic '" + topic + "'", e);
		}
	}

	/**
	 * Returns the memoised producer for the subscription with the given id.
	 *
	 * @param subscriptionId the subscription id
	 * @return the reusable producer, never {@code null}
	 * @throws EventsException when no subscription has that id, or the producer cannot be created
	 */
	IProducer producer(String subscriptionId) throws EventsException {
		Resolved resolved = resolveById(subscriptionId);
		return producerFor(resolved.runtime(), resolved.subscription());
	}

	/** Resolves the first (runtime, subscription) whose subscription topic equals {@code topic}. */
	private Resolved resolveByTopic(String topic) throws EventsException {
		for (Map<String, ClusterRuntime> clusters : runtimes.values()) {
			for (ClusterRuntime runtime : clusters.values()) {
				for (SubscriptionDef sub : runtime.getSubscriptions().values()) {
					if (sub.topic() != null && sub.topic().equals(topic)) {
						return new Resolved(runtime, sub);
					}
				}
			}
		}
		throw new EventsException("no subscription with topic '" + topic + "' in the events topology");
	}

	/** Resolves the (runtime, subscription) whose subscription id equals {@code subscriptionId}. */
	private Resolved resolveById(String subscriptionId) throws EventsException {
		for (Map<String, ClusterRuntime> clusters : runtimes.values()) {
			for (ClusterRuntime runtime : clusters.values()) {
				SubscriptionDef sub = runtime.getSubscriptions().get(subscriptionId);
				if (sub != null) {
					return new Resolved(runtime, sub);
				}
			}
		}
		throw new EventsException("no subscription '" + subscriptionId + "'");
	}

	/**
	 * Returns (memoising) the producer for a subscription, created via the engine's own connector
	 * instance. Creation failures surfaced from {@code computeIfAbsent} are rethrown as
	 * {@link EventsException} at this call site.
	 */
	private IProducer producerFor(ClusterRuntime runtime, SubscriptionDef sub) throws EventsException {
		try {
			return publishProducers.computeIfAbsent(sub.id(), k -> {
				IConnector connector = runtime.getConnectors().get(sub.connector());
				if (connector == null) {
					throw new ProducerCreationException(
							"connector '" + sub.connector() + "' not found for subscription '" + sub.id() + "'");
				}
				DataflowDef df = runtime.getDataflows().get(sub.dataflow());
				return connector.createProducer(sub, df);
			});
		} catch (ProducerCreationException e) {
			throw new EventsException(e.getMessage(), e);
		}
	}

	/** Best-effort stop of every ad-hoc producer, then clears the cache. */
	void close() {
		for (IProducer producer : publishProducers.values()) {
			try {
				producer.stop();
			} catch (Exception e) {
				log.warn("Error stopping ad-hoc producer", e);
			}
		}
		publishProducers.clear();
	}
}
