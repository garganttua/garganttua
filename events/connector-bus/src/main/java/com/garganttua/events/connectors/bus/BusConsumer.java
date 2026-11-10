package com.garganttua.events.connectors.bus;

import java.util.function.Consumer;

import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.exceptions.ConnectorException;
import com.leansoft.bigqueue.IBigQueue;

import com.garganttua.core.observability.Logger;

public class BusConsumer implements IConsumer {

	private static final Logger log = Logger.getLogger(BusConsumer.class);

	private final IBigQueue queue;
	private final Object lock;
	private final long pollIntervalMs;
	private volatile boolean running;

	// EI_EXPOSE_REP2: the IBigQueue is a shared resource owned and lifecycle-managed by the
	// connector; it is deliberately handed off (not copied) so consumer and connector share it.
	@SuppressFBWarnings(value = "EI_EXPOSE_REP2",
			justification = "queue is a shared, connector-managed resource handed off by reference")
	public BusConsumer(IBigQueue queue, long pollIntervalMs) {
		this(queue, new Object(), pollIntervalMs);
	}

	/**
	 * @param queue          the topic's queue, shared with the connector
	 * @param lock           the queue's lock: an acknowledging consumer of the same queue holds it while its route
	 *                       runs, and the head must not be taken from under it
	 * @param pollIntervalMs how long an empty queue is left before it is looked at again
	 */
	@SuppressFBWarnings(value = "EI_EXPOSE_REP2",
			justification = "queue and its lock are shared, connector-managed resources handed off by reference")
	public BusConsumer(IBigQueue queue, Object lock, long pollIntervalMs) {
		this.queue = queue;
		this.lock = lock;
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
					byte[] bytes;
					synchronized (lock) {
						bytes = queue.dequeue();
					}
					if (bytes == null) {
						continue;
					}
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
