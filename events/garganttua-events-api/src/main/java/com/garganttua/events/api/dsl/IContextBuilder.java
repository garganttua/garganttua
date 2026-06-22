package com.garganttua.events.api.dsl;

import com.garganttua.core.dsl.ILinkedBuilder;
import com.garganttua.events.api.context.ContextDef;
import com.garganttua.events.api.enums.PublicationMode;

public interface IContextBuilder extends ILinkedBuilder<IEngineBuilder, ContextDef> {

	IContextBuilder topic(String ref);

	IContextBuilder dataflow(String uuid, String name, String type,
			boolean garanteeOrder, String version, boolean encapsulated);

	IConnectorConfigBuilder connector(String name, String type, String version);

	ISubscriptionBuilder subscription(String id, String dataflow, String topic,
			String connector, PublicationMode publicationMode);

	IRouteBuilder route(String uuid, String from);

	IContextBuilder lock(String name, String type, String version);
}
