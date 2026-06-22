package com.garganttua.events.core.dsl;

import com.garganttua.core.dsl.DslException;
import com.garganttua.events.api.context.ConsumerConfigurationDef;
import com.garganttua.events.api.dsl.IConsumerConfigBuilder;
import com.garganttua.events.api.dsl.ISubscriptionBuilder;
import com.garganttua.events.api.enums.DestinationPolicy;
import com.garganttua.events.api.enums.HighAvailabilityMode;
import com.garganttua.events.api.enums.OriginPolicy;
import com.garganttua.events.api.enums.ProcessMode;

public class ConsumerConfigBuilder implements IConsumerConfigBuilder {

	private ISubscriptionBuilder parent;
	private ProcessMode processMode = ProcessMode.EVERYBODY;
	private OriginPolicy originPolicy = OriginPolicy.FROM_ANY;
	private DestinationPolicy destinationPolicy = DestinationPolicy.TO_ANY;
	private HighAvailabilityMode highAvailabilityMode;

	@Override
	public IConsumerConfigBuilder processMode(ProcessMode mode) {
		this.processMode = mode;
		return this;
	}

	@Override
	public IConsumerConfigBuilder originPolicy(OriginPolicy policy) {
		this.originPolicy = policy;
		return this;
	}

	@Override
	public IConsumerConfigBuilder destinationPolicy(DestinationPolicy policy) {
		this.destinationPolicy = policy;
		return this;
	}

	@Override
	public IConsumerConfigBuilder highAvailabilityMode(HighAvailabilityMode mode) {
		this.highAvailabilityMode = mode;
		return this;
	}

	@Override
	public ISubscriptionBuilder up() {
		if (parent instanceof SubscriptionBuilder sb) {
			sb.setConsumerConfig(build());
		}
		return parent;
	}

	@Override
	public void setUp(ISubscriptionBuilder up) {
		this.parent = up;
	}

	@Override
	public ConsumerConfigurationDef build() throws DslException {
		return new ConsumerConfigurationDef(processMode, originPolicy, destinationPolicy, highAvailabilityMode);
	}
}
