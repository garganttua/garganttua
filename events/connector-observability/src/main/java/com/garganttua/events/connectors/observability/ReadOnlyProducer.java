package com.garganttua.events.connectors.observability;

import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.exceptions.ConnectorException;

/**
 * A producer for an input-only connector. Observability connectors only observe; they never
 * publish back to their source, so every {@link #publish(byte[])} call fails fast with a
 * {@link ConnectorException} rather than silently dropping the message.
 */
public final class ReadOnlyProducer implements IProducer {

	@Override
	public void publish(byte[] value) throws ConnectorException {
		throw new ConnectorException("observability connector is read-only");
	}

	@Override
	public void stop() throws ConnectorException {
		// nothing to release
	}
}
