package com.garganttua.events.connectors.bus;

import java.io.IOException;

import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.exceptions.ConnectorException;
import com.leansoft.bigqueue.IBigQueue;

public class BusProducer implements IProducer {

	private final IBigQueue queue;
	private final String dataflowUuid;

	// EI_EXPOSE_REP2: the IBigQueue is a shared resource owned and lifecycle-managed by the
	// connector; it is deliberately handed off (not copied) so producer and connector share it.
	@SuppressFBWarnings(value = "EI_EXPOSE_REP2",
			justification = "queue is a shared, connector-managed resource handed off by reference")
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
