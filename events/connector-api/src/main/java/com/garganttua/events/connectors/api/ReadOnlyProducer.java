package com.garganttua.events.connectors.api;

import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.exceptions.ConnectorException;

/**
 * A producer for an input-only connector. The api-events connector only observes api business
 * events; it never publishes back, so every {@link #publish(byte[])} call fails fast with a
 * {@link ConnectorException} rather than silently dropping the message.
 */
public final class ReadOnlyProducer implements IProducer {

	@Override
	public void publish(byte[] value) throws ConnectorException {
		throw new ConnectorException("api events connector is read-only");
	}

	@Override
	public void stop() throws ConnectorException {
		// nothing to release
	}
}
