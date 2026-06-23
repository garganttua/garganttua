package com.garganttua.events.api.dsl;

import com.garganttua.core.dsl.ILinkedBuilder;
import com.garganttua.events.api.context.ConnectorDef;

public interface IConnectorConfigBuilder extends ILinkedBuilder<IContextBuilder, ConnectorDef> {

	IConnectorConfigBuilder config(String key, String value);
}
