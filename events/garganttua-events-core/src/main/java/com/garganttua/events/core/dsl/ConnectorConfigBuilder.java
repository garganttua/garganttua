package com.garganttua.events.core.dsl;

import java.util.HashMap;
import java.util.Map;

import com.garganttua.core.dsl.DslException;
import com.garganttua.events.api.context.ConnectorDef;
import com.garganttua.events.api.dsl.IConnectorConfigBuilder;
import com.garganttua.events.api.dsl.IContextBuilder;

public class ConnectorConfigBuilder implements IConnectorConfigBuilder {

	private IContextBuilder parent;
	private final String name;
	private final String type;
	private final String version;
	private final Map<String, String> configuration = new HashMap<>();

	public ConnectorConfigBuilder(String name, String type, String version) {
		this.name = name;
		this.type = type;
		this.version = version;
	}

	@Override
	public IConnectorConfigBuilder config(String key, String value) {
		configuration.put(key, value);
		return this;
	}

	@Override
	public IContextBuilder up() {
		if (parent instanceof ContextBuilder cb) {
			cb.addConnector(build());
		}
		return parent;
	}

	@Override
	public void setUp(IContextBuilder up) {
		this.parent = up;
	}

	@Override
	public ConnectorDef build() throws DslException {
		return new ConnectorDef(name, type, version, configuration);
	}
}
