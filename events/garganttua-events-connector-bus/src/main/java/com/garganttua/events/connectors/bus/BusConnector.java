package com.garganttua.events.connectors.bus;

import java.io.File;
import java.io.IOException;
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
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.SubscriptionDef;
import com.leansoft.bigqueue.BigQueueImpl;
import com.leansoft.bigqueue.IBigQueue;

import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;

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
	private ConnectorContext ctx;
	private String queuesDir;
	private int pollInterval = 10;
	private TimeUnit pollIntervalUnit = TimeUnit.SECONDS;
	private final Map<String, IBigQueue> queues = new HashMap<>();

	@Override
	public void configure(Map<String, String> configuration, ConnectorContext ctx) {
		this.ctx = ctx;
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

	private IBigQueue getOrCreateQueue(String topicRef) {
		return queues.computeIfAbsent(topicRef, ref -> {
			try {
				return new BigQueueImpl(queuesDir, formatTopicRef(ref));
			} catch (IOException e) {
				log.error("Unable to create queue for topic {}", ref, e);
				throw new RuntimeException(e);
			}
		});
	}

	@Override
	public IConsumer createConsumer(SubscriptionDef sub, DataflowDef df) {
		IBigQueue queue = getOrCreateQueue(sub.topic());
		long pollMs = TimeUnit.MILLISECONDS.convert(pollInterval, pollIntervalUnit);
		return new BusConsumer(queue, pollMs);
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
