package com.garganttua.events.api;

import java.util.function.Consumer;

import com.garganttua.events.api.exceptions.ConnectorException;

public interface IConsumer {

	void start(Consumer<byte[]> messageHandler) throws ConnectorException;

	void stop() throws ConnectorException;
}
