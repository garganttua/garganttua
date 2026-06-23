package com.garganttua.events.api;

import com.garganttua.events.api.exceptions.ConnectorException;

public interface IProducer {

	void publish(byte[] value) throws ConnectorException;

	void stop() throws ConnectorException;
}
