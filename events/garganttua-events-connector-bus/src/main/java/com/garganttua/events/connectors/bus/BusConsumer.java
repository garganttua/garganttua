package com.garganttua.events.connectors.bus;

import java.util.function.Consumer;

import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.exceptions.ConnectorException;
import com.leansoft.bigqueue.IBigQueue;

import com.garganttua.core.observability.Logger;

public class BusConsumer implements IConsumer {

	private static final Logger log = Logger.getLogger(BusConsumer.class);

	private final IBigQueue queue;
	private final long pollIntervalMs;
	private volatile boolean running;

	public BusConsumer(IBigQueue queue, long pollIntervalMs) {
		this.queue = queue;
		this.pollIntervalMs = pollIntervalMs;
	}

	@Override
	public void start(Consumer<byte[]> messageHandler) throws ConnectorException {
		this.running = true;
		while (running) {
			try {
				Thread.sleep(pollIntervalMs);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}

			while (!queue.isEmpty() && running) {
				try {
					byte[] bytes = queue.dequeue();
					BusMessage msg = BusMessage.fromBytes(bytes);
					messageHandler.accept(msg.value());
				} catch (Exception e) {
					log.warn("Error reading message from bus queue", e);
				}
			}
		}
	}

	@Override
	public void stop() throws ConnectorException {
		this.running = false;
	}
}
