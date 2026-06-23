package com.garganttua.events.api.dsl;

import com.garganttua.core.dsl.ILinkedBuilder;
import com.garganttua.events.api.context.ProducerConfigurationDef;
import com.garganttua.events.api.enums.DestinationPolicy;

public interface IProducerConfigBuilder extends ILinkedBuilder<ISubscriptionBuilder, ProducerConfigurationDef> {

	IProducerConfigBuilder destinationPolicy(DestinationPolicy policy);

	IProducerConfigBuilder destinationUuid(String uuid);
}
