package com.garganttua.events.connectors.bus;

import java.io.IOException;

import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.exceptions.ConnectorException;
import com.leansoft.bigqueue.IBigQueue;

public class BusProducer implements IProducer {

	private final IBigQueue queue;
	private final String dataflowUuid;

	public BusProducer(IBigQueue queue, String dataflowUuid) {
		this.queue = queue;
		this.dataflowUuid = dataflowUuid;
	}

	@Override
	public void publish(byte[] value) throws ConnectorException {
		try {
			BusMessage message = new BusMessage(dataflowUuid, value);
			queue.enqueue(message.toBytes());
		} catch (IOException e) {
			throw new ConnectorException(e);
		}
	}

	@Override
	public void stop() throws ConnectorException {
		// Nothing to do - queue is managed by connector
	}
}
