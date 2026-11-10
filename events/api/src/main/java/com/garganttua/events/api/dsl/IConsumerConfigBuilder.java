package com.garganttua.events.api.dsl;

import com.garganttua.core.dsl.ILinkedBuilder;
import com.garganttua.events.api.context.QualityOfService;
import com.garganttua.events.api.context.ConsumerConfigurationDef;
import com.garganttua.events.api.enums.DestinationPolicy;
import com.garganttua.events.api.enums.HighAvailabilityMode;
import com.garganttua.events.api.enums.OriginPolicy;
import com.garganttua.events.api.enums.ProcessMode;

public interface IConsumerConfigBuilder extends ILinkedBuilder<ISubscriptionBuilder, ConsumerConfigurationDef> {

	IConsumerConfigBuilder processMode(ProcessMode mode);

	IConsumerConfigBuilder originPolicy(OriginPolicy policy);

	IConsumerConfigBuilder destinationPolicy(DestinationPolicy policy);

	IConsumerConfigBuilder highAvailabilityMode(HighAvailabilityMode mode);

	/**
	 * Sets the number of parallel workers for this subscription's routes ({@code 1} = sequential).
	 * Honoured only when the dataflow does not guarantee order.
	 *
	 * @param concurrency the worker count ({@code <= 1} means sequential)
	 * @return this builder
	 */
	IConsumerConfigBuilder concurrency(int concurrency);

	/**
	 * When this subscription's consumer acknowledges a message to its transport. Not declared: each
	 * connector's own behaviour.
	 *
	 * <ul>
	 *   <li>{@link QualityOfService#AT_MOST_ONCE} — on receipt; a message whose route fails is lost.</li>
	 *   <li>{@link QualityOfService#AT_LEAST_ONCE} — once the message is safe (route succeeded, or handed to the
	 *       route's error subscription); else delivered again. The connector must be able to acknowledge after
	 *       the route — Kafka and BigQueue can — or the engine refuses to start the route.</li>
	 * </ul>
	 *
	 * @param qualityOfService the quality of service
	 * @return this builder
	 * @since 3.0.0-ALPHA34
	 */
	IConsumerConfigBuilder qualityOfService(QualityOfService qualityOfService);
}
