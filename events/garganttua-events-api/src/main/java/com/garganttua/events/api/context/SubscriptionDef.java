package com.garganttua.events.api.context;

import com.garganttua.events.api.enums.PublicationMode;

public record SubscriptionDef(
		String id,
		String dataflow,
		String topic,
		String connector,
		PublicationMode publicationMode,
		ConsumerConfigurationDef consumerConfiguration,
		ProducerConfigurationDef producerConfiguration,
		TimeIntervalDef timeInterval) {}
