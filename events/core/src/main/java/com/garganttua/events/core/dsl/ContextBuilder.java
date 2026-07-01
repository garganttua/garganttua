package com.garganttua.events.core.dsl;

import java.util.ArrayList;
import java.util.List;

import com.garganttua.core.dsl.DslException;
import com.garganttua.events.api.context.ConnectorDef;
import com.garganttua.events.api.context.ContextDef;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.LockDef;
import com.garganttua.events.api.context.RouteDef;
import com.garganttua.events.api.context.SubscriptionDef;
import com.garganttua.events.api.context.TopicDef;
import com.garganttua.events.api.dsl.IConnectorConfigBuilder;
import com.garganttua.events.api.dsl.IContextBuilder;
import com.garganttua.events.api.dsl.IEventsBuilder;
import com.garganttua.events.api.dsl.IRouteBuilder;
import com.garganttua.events.api.dsl.ISubscriptionBuilder;
import com.garganttua.events.api.enums.PublicationMode;

import java.util.Collections;
import java.util.HashMap;

public class ContextBuilder implements IContextBuilder {

	private IEventsBuilder parent;
	private final String tenantId;
	private final String clusterId;
	private final List<TopicDef> topics = new ArrayList<>();
	private final List<DataflowDef> dataflows = new ArrayList<>();
	private final List<ConnectorDef> connectors = new ArrayList<>();
	private final List<SubscriptionDef> subscriptions = new ArrayList<>();
	private final List<RouteDef> routes = new ArrayList<>();
	private final List<LockDef> locks = new ArrayList<>();

	public ContextBuilder(String tenantId, String clusterId) {
		this.tenantId = tenantId;
		this.clusterId = clusterId;
	}

	@Override
	public IContextBuilder topic(String ref) {
		topics.add(new TopicDef(ref));
		return this;
	}

	@Override
	public IContextBuilder dataflow(String uuid, String name, String type,
			boolean garanteeOrder, String version, boolean encapsulated) {
		dataflows.add(new DataflowDef(uuid, name, type, garanteeOrder, version, encapsulated));
		return this;
	}

	@Override
	public IConnectorConfigBuilder connector(String name, String type, String version) {
		ConnectorConfigBuilder builder = new ConnectorConfigBuilder(name, type, version);
		builder.setUp(this);
		return builder;
	}

	@Override
	public ISubscriptionBuilder subscription(String id, String dataflow, String topic,
			String connector, PublicationMode publicationMode) {
		SubscriptionBuilder builder = new SubscriptionBuilder(id, dataflow, topic, connector, publicationMode);
		builder.setUp(this);
		return builder;
	}

	@Override
	public IRouteBuilder route(String uuid, String from) {
		RouteBuilder builder = new RouteBuilder(uuid, from);
		builder.setUp(this);
		return builder;
	}

	@Override
	public IContextBuilder lock(String name, String type, String version) {
		locks.add(new LockDef(name, type, version, new HashMap<>()));
		return this;
	}

	void addConnector(ConnectorDef def) {
		connectors.add(def);
	}

	void addSubscription(SubscriptionDef def) {
		subscriptions.add(def);
	}

	void addRoute(RouteDef def) {
		routes.add(def);
	}

	/**
	 * The parent {@link EventsBuilder}, or {@code null} when this context builder was created outside
	 * the events DSL chain. Used by {@link RouteBuilder} to register route-supplied mutex beans.
	 *
	 * @return the parent events builder, or {@code null}
	 */
	EventsBuilder eventsBuilder() {
		return parent instanceof EventsBuilder eb ? eb : null;
	}

	@Override
	public IEventsBuilder up() {
		if (parent instanceof EventsBuilder eb) {
			eb.addContext(build());
		}
		return parent;
	}

	@Override
	public void setUp(IEventsBuilder up) {
		this.parent = up;
	}

	@Override
	public ContextDef build() throws DslException {
		return new ContextDef(tenantId, clusterId,
				Collections.unmodifiableList(topics),
				Collections.unmodifiableList(dataflows),
				Collections.unmodifiableList(connectors),
				Collections.unmodifiableList(subscriptions),
				Collections.unmodifiableList(routes),
				Collections.unmodifiableList(locks));
	}
}
