package com.garganttua.events.core.dsl;

import com.garganttua.core.dsl.DslException;
import com.garganttua.events.api.context.ConsumerConfigurationDef;
import com.garganttua.events.api.context.ProducerConfigurationDef;
import com.garganttua.events.api.context.SubscriptionDef;
import com.garganttua.events.api.dsl.IConsumerConfigBuilder;
import com.garganttua.events.api.dsl.IContextBuilder;
import com.garganttua.events.api.dsl.IProducerConfigBuilder;
import com.garganttua.events.api.dsl.ISubscriptionBuilder;
import com.garganttua.events.api.enums.PublicationMode;

public class SubscriptionBuilder implements ISubscriptionBuilder {

	private IContextBuilder parent;
	private final String id;
	private final String dataflow;
	private final String topic;
	private final String connector;
	private final PublicationMode publicationMode;
	private ConsumerConfigurationDef consumerConfig;
	private ProducerConfigurationDef producerConfig;

	public SubscriptionBuilder(String id, String dataflow, String topic,
			String connector, PublicationMode publicationMode) {
		this.id = id;
		this.dataflow = dataflow;
		this.topic = topic;
		this.connector = connector;
		this.publicationMode = publicationMode;
	}

	@Override
	public IConsumerConfigBuilder consumer() {
		ConsumerConfigBuilder builder = new ConsumerConfigBuilder();
		builder.setUp(this);
		return builder;
	}

	@Override
	public IProducerConfigBuilder producer() {
		ProducerConfigBuilder builder = new ProducerConfigBuilder();
		builder.setUp(this);
		return builder;
	}

	void setConsumerConfig(ConsumerConfigurationDef config) {
		this.consumerConfig = config;
	}

	void setProducerConfig(ProducerConfigurationDef config) {
		this.producerConfig = config;
	}

	@Override
	public IContextBuilder up() {
		if (parent instanceof ContextBuilder cb) {
			cb.addSubscription(build());
		}
		return parent;
	}

	@Override
	public void setUp(IContextBuilder up) {
		this.parent = up;
	}

	@Override
	public SubscriptionDef build() throws DslException {
		return new SubscriptionDef(id, dataflow, topic, connector, publicationMode,
				consumerConfig, producerConfig, null);
	}
}
