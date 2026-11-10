package com.garganttua.events.api.context;

import com.garganttua.events.api.enums.DestinationPolicy;
import com.garganttua.events.api.enums.HighAvailabilityMode;
import com.garganttua.events.api.enums.OriginPolicy;
import com.garganttua.events.api.enums.ProcessMode;

/**
 * Per-subscription consumer configuration.
 *
 * @param concurrency the number of parallel workers processing messages on this subscription's
 *                    routes ({@code 1} = sequential). Honoured only when the dataflow does NOT
 *                    guarantee order; an order-guaranteeing dataflow always runs sequentially.
 * @param qualityOfService when the consumer acknowledges a message ({@link QualityOfService}); {@code null}
 *                    when not declared — each connector's own behaviour
 */
public record ConsumerConfigurationDef(
		ProcessMode processMode,
		OriginPolicy originPolicy,
		DestinationPolicy destinationPolicy,
		HighAvailabilityMode highAvailabilityMode,
		int concurrency,
		QualityOfService qualityOfService) {

	/** A configuration that declares no quality of service — each connector's own behaviour. */
	public ConsumerConfigurationDef(ProcessMode processMode, OriginPolicy originPolicy,
			DestinationPolicy destinationPolicy, HighAvailabilityMode highAvailabilityMode, int concurrency) {
		this(processMode, originPolicy, destinationPolicy, highAvailabilityMode, concurrency, null);
	}
}
