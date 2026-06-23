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
}
