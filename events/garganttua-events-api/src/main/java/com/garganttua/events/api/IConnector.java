package com.garganttua.events.api;

import java.util.Map;

import com.garganttua.core.lifecycle.ILifecycle;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.SubscriptionDef;

public interface IConnector extends ILifecycle {

	String getName();

	void configure(Map<String, String> configuration, ConnectorContext ctx);

	IConsumer createConsumer(SubscriptionDef sub, DataflowDef df);

	IProducer createProducer(SubscriptionDef sub, DataflowDef df);
}
