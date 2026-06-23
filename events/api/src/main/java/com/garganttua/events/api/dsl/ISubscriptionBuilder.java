package com.garganttua.events.api.dsl;

import com.garganttua.core.dsl.ILinkedBuilder;
import com.garganttua.events.api.context.SubscriptionDef;

public interface ISubscriptionBuilder extends ILinkedBuilder<IContextBuilder, SubscriptionDef> {

	IConsumerConfigBuilder consumer();

	IProducerConfigBuilder producer();
}
