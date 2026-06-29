package com.garganttua.events.api.dsl;

import com.garganttua.core.dsl.ILinkedBuilder;
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
}
