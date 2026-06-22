package com.garganttua.events.core.dsl;

import com.garganttua.core.dsl.DslException;
import com.garganttua.events.api.context.ProducerConfigurationDef;
import com.garganttua.events.api.dsl.IProducerConfigBuilder;
import com.garganttua.events.api.dsl.ISubscriptionBuilder;
import com.garganttua.events.api.enums.DestinationPolicy;

public class ProducerConfigBuilder implements IProducerConfigBuilder {

	private ISubscriptionBuilder parent;
	private DestinationPolicy destinationPolicy = DestinationPolicy.TO_ANY;
	private String destinationUuid;

	@Override
	public IProducerConfigBuilder destinationPolicy(DestinationPolicy policy) {
		this.destinationPolicy = policy;
		return this;
	}

	@Override
	public IProducerConfigBuilder destinationUuid(String uuid) {
		this.destinationUuid = uuid;
		return this;
	}

	@Override
	public ISubscriptionBuilder up() {
		if (parent instanceof SubscriptionBuilder sb) {
			sb.setProducerConfig(build());
		}
		return parent;
	}

	@Override
	public void setUp(ISubscriptionBuilder up) {
		this.parent = up;
	}

	@Override
	public ProducerConfigurationDef build() throws DslException {
		return new ProducerConfigurationDef(destinationPolicy, destinationUuid);
	}
}
