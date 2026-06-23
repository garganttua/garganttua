package com.garganttua.events.connectors.kafka;

import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.RoundRobinAssignor;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import com.garganttua.core.lifecycle.AbstractLifecycle;
import com.garganttua.core.lifecycle.ILifecycle;
import com.garganttua.core.lifecycle.LifecycleException;
import com.garganttua.events.api.ConnectorContext;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.SubscriptionDef;
import com.garganttua.events.api.enums.ProcessMode;

import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.annotations.Reflected;
import com.garganttua.events.api.connectors.annotations.Connector;

@Connector(type = "kafka")
@Reflected
public class KafkaConnector extends AbstractLifecycle implements IConnector {

	@Override
	public IReflection reflection() {
		return IClass.getReflection();
	}

	private String name;

	@Override
	public String getName() {
		return this.name;
	}
	// ctx is populated by configure() as part of the IConnector lifecycle contract (not the
	// constructor); reads are guarded by that ordering, so the not-initialized-in-ctor finding is moot.
	@SuppressFBWarnings(value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR",
			justification = "Set in configure() per IConnector lifecycle, not the constructor.")
	private ConnectorContext ctx;

	private String kafkaBrokerUrl;
	private int maxPollRecordsConfig = 1;
	private String enableAutoCommitConfig = "false";
	private String autoOffsetResetConfig = "latest";
	private boolean allowAutoCreateTopics = false;
	private int pollInterval = 10;
	private TimeUnit pollIntervalUnit = TimeUnit.SECONDS;

	@Override
	public void configure(Map<String, String> configuration, ConnectorContext ctx) {
		this.ctx = ctx;
		this.name = configuration.getOrDefault("name", "kafka");
		this.kafkaBrokerUrl = configuration.get("url");
		if (configuration.containsKey("maxPollRecords")) {
			this.maxPollRecordsConfig = Integer.parseInt(configuration.get("maxPollRecords"));
		}
		if (configuration.containsKey("enableAutoCommit")) {
			this.enableAutoCommitConfig = configuration.get("enableAutoCommit");
		}
		if (configuration.containsKey("autoOffsetReset")) {
			this.autoOffsetResetConfig = configuration.get("autoOffsetReset");
		}
		if (configuration.containsKey("allowAutoCreateTopics")) {
			this.allowAutoCreateTopics = Boolean.parseBoolean(configuration.get("allowAutoCreateTopics"));
		}
		if (configuration.containsKey("pollInterval")) {
			this.pollInterval = Integer.parseInt(configuration.get("pollInterval"));
		}
		if (configuration.containsKey("pollIntervalUnit")) {
			this.pollIntervalUnit = TimeUnit.valueOf(configuration.get("pollIntervalUnit"));
		}
	}

	private String formatTopicRef(String topicRef) {
		return topicRef.replace("/", "-").substring(1);
	}

	@Override
	public IConsumer createConsumer(SubscriptionDef sub, DataflowDef df) {
		Properties props = new Properties();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBrokerUrl);

		ProcessMode processMode = sub.consumerConfiguration() != null
				? sub.consumerConfiguration().processMode() : ProcessMode.EVERYBODY;
		String groupId;
		if (processMode == ProcessMode.ONLY_ONE_CLUSTER_NODE) {
			groupId = "C_" + ctx.tenantId() + "_" + sub.dataflow() + "_" + formatTopicRef(sub.topic()) + "_" + ctx.clusterId();
		} else {
			groupId = "C_" + ctx.tenantId() + "_" + sub.dataflow() + "_" + formatTopicRef(sub.topic()) + "_" + ctx.clusterId() + "_" + ctx.assetId();
		}
		props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
		props.put(ConsumerConfig.CLIENT_ID_CONFIG, "C_" + ctx.assetId() + "_" + sub.dataflow() + "_" + formatTopicRef(sub.topic()));
		props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, allowAutoCreateTopics);
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
		props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecordsConfig);
		props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, enableAutoCommitConfig);
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetResetConfig);
		props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RoundRobinAssignor.class.getName());

		Consumer<String, byte[]> consumer = new KafkaConsumer<>(props);
		consumer.subscribe(Collections.singletonList(formatTopicRef(sub.topic())));

		long pollMs = TimeUnit.MILLISECONDS.convert(pollInterval, pollIntervalUnit);
		return new KafkaConsumer_(consumer, Boolean.parseBoolean(enableAutoCommitConfig), pollMs);
	}

	@Override
	public IProducer createProducer(SubscriptionDef sub, DataflowDef df) {
		Properties props = new Properties();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBrokerUrl);
		props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, allowAutoCreateTopics);
		props.put(ConsumerConfig.CLIENT_ID_CONFIG, "P_" + ctx.assetId() + "_" + sub.dataflow() + "_" + formatTopicRef(sub.topic()));
		props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
		props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());

		KafkaProducer<String, byte[]> kafkaProducer = new KafkaProducer<>(props);
		return new KafkaProducer_(kafkaProducer, formatTopicRef(sub.topic()), sub.dataflow());
	}

	@Override
	protected ILifecycle doInit() throws LifecycleException {
		return this;
	}

	@Override
	protected ILifecycle doStart() throws LifecycleException {
		return this;
	}

	@Override
	protected ILifecycle doFlush() throws LifecycleException {
		return this;
	}

	@Override
	protected ILifecycle doStop() throws LifecycleException {
		return this;
	}
}
