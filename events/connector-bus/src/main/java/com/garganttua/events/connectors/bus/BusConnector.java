package com.garganttua.events.connectors.bus;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.garganttua.core.lifecycle.AbstractLifecycle;
import com.garganttua.core.lifecycle.ILifecycle;
import com.garganttua.core.lifecycle.LifecycleException;
import com.garganttua.events.api.ConnectorContext;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.context.QualityOfService;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.SubscriptionDef;
import com.leansoft.bigqueue.BigQueueImpl;
import com.leansoft.bigqueue.IBigQueue;

import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.annotations.Reflected;
import com.garganttua.events.api.connectors.annotations.Connector;

@Connector(type = "bus")
@Reflected
public class BusConnector extends AbstractLifecycle implements IConnector {

	private static final Logger log = Logger.getLogger(BusConnector.class);

	@Override
	public IReflection reflection() {
		return IClass.getReflection();
	}

	private String name;

	@Override
	public String getName() {
		return this.name;
	}
	private String queuesDir;
	private int pollInterval = 10;
	private TimeUnit pollIntervalUnit = TimeUnit.SECONDS;
	private final Map<String, IBigQueue> queues = new HashMap<>();
	/** One lock per queue: an acknowledging consumer reads and removes the head under it. */
	private final Map<String, Object> locks = new HashMap<>();

	@Override
	public void configure(Map<String, String> configuration, ConnectorContext ctx) {
		this.name = configuration.getOrDefault("name", "bus");
		String homeDir = configuration.getOrDefault("homeDirectory", System.getProperty("java.io.tmpdir"));
		this.queuesDir = homeDir + File.separator + ctx.assetId() + File.separator + ctx.tenantId() + File.separator + ctx.clusterId();
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

	/** The lock of a topic's queue — the same for every consumer of it, whatever its quality of service. */
	private Object lockOf(String topicRef) {
		return locks.computeIfAbsent(topicRef, ref -> new Object());
	}

	private IBigQueue getOrCreateQueue(String topicRef) {
		return queues.computeIfAbsent(topicRef, ref -> {
			try {
				return new BigQueueImpl(queuesDir, formatTopicRef(ref));
			} catch (IOException e) {
				log.error("Unable to create queue for topic {}", ref, e);
				throw new UncheckedIOException(e);
			}
		});
	}

	@Override
	public IConsumer createConsumer(SubscriptionDef sub, DataflowDef df) {
		IBigQueue queue = getOrCreateQueue(sub.topic());
		long pollMs = TimeUnit.MILLISECONDS.convert(pollInterval, pollIntervalUnit);
		if (sub.consumerConfiguration() != null
				&& sub.consumerConfiguration().qualityOfService() == QualityOfService.AT_LEAST_ONCE) {
			return new BusAcknowledgingConsumer(queue, lockOf(sub.topic()), pollMs);
		}
		return new BusConsumer(queue, lockOf(sub.topic()), pollMs);
	}

	@Override
	public IProducer createProducer(SubscriptionDef sub, DataflowDef df) {
		IBigQueue queue = getOrCreateQueue(sub.topic());
		return new BusProducer(queue, df.uuid());
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
		queues.clear();
		locks.clear();
		return this;
	}

	@Override
	protected ILifecycle doStop() throws LifecycleException {
		for (IBigQueue queue : queues.values()) {
			try {
				queue.close();
			} catch (IOException e) {
				log.warn("Error closing queue", e);
			}
		}
		return this;
	}
}
