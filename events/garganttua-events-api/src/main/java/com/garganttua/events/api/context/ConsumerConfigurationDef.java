package com.garganttua.events.api.context;

import com.garganttua.events.api.enums.DestinationPolicy;
import com.garganttua.events.api.enums.HighAvailabilityMode;
import com.garganttua.events.api.enums.OriginPolicy;
import com.garganttua.events.api.enums.ProcessMode;

public record ConsumerConfigurationDef(
		ProcessMode processMode,
		OriginPolicy originPolicy,
		DestinationPolicy destinationPolicy,
		HighAvailabilityMode highAvailabilityMode) {}
