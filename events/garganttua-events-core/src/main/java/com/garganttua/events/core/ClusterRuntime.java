package com.garganttua.events.core;

import java.util.HashMap;
import java.util.Map;

import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.IDistributedLock;
import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.context.ContextDef;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.SubscriptionDef;
import com.garganttua.events.api.context.TopicDef;
import com.garganttua.core.workflow.IWorkflow;

public class ClusterRuntime {

	private final ContextDef context;
	private final Map<String, IConnector> connectors = new HashMap<>();
	private final Map<String, TopicDef> topics = new HashMap<>();
	private final Map<String, DataflowDef> dataflows = new HashMap<>();
	private final Map<String, SubscriptionDef> subscriptions = new HashMap<>();
	private final Map<String, IWorkflow> routeWorkflows = new HashMap<>();
	private final Map<String, IConsumer> consumers = new HashMap<>();
	private final Map<String, IProducer> producers = new HashMap<>();
	private final Map<String, IDistributedLock> locks = new HashMap<>();

	public ClusterRuntime(ContextDef context) {
		this.context = context;
	}

	public ContextDef getContext() {
		return context;
	}

	public Map<String, IConnector> getConnectors() {
		return connectors;
	}

	public Map<String, TopicDef> getTopics() {
		return topics;
	}

	public Map<String, DataflowDef> getDataflows() {
		return dataflows;
	}

	public Map<String, SubscriptionDef> getSubscriptions() {
		return subscriptions;
	}

	public Map<String, IWorkflow> getRouteWorkflows() {
		return routeWorkflows;
	}

	public Map<String, IConsumer> getConsumers() {
		return consumers;
	}

	public Map<String, IProducer> getProducers() {
		return producers;
	}

	public Map<String, IDistributedLock> getLocks() {
		return locks;
	}
}
