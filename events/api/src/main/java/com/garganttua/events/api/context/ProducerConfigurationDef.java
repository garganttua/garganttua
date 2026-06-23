package com.garganttua.events.api.context;

import com.garganttua.events.api.enums.DestinationPolicy;

public record ProducerConfigurationDef(
		DestinationPolicy destinationPolicy,
		String destinationUuid) {}
