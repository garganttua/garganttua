package com.garganttua.events.connectors.bus;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

import com.garganttua.core.observability.Logger;
import com.garganttua.events.api.IAcknowledgingConsumer;
import com.garganttua.events.api.exceptions.ConnectorException;
import com.leansoft.bigqueue.IBigQueue;

/**
 * A BigQueue consumer that removes a message from the disk only once it is SAFE — the route succeeded, or
 * handed it to its error subscription — for a subscription declaring
 * {@link com.garganttua.events.api.context.QualityOfService#AT_LEAST_ONCE}.
 *
 * <p>
 * It reads the head of the queue without removing it ({@code peek}), awaits the route, and removes it on
 * success; otherwise the message stays at the head and is tried again after a wait, longer each time (from
 * 200 ms, doubling, at most 5 s). A message that never succeeds and has no error subscription therefore holds
 * the queue — an audit trail must stop rather than lose. The queue of a topic is shared by every consumer of
 * that topic on the node, so the head is read and removed under the queue's own lock: one message at a time
 * per queue. A message that cannot even be decoded is removed with an error: no route can ever take it.
 * </p>
 */
public class BusAcknowledgingConsumer implements IAcknowledgingConsumer {

	private static final Logger LOG = Logger.getLogger(BusAcknowledgingConsumer.class);
	private static final long FIRST_RETRY_DELAY_MILLIS = 200L;
	private static final long MAX_RETRY_DELAY_MILLIS = 5_000L;

	private final IBigQueue queue;
	private final Object lock;
	private final long pollIntervalMs;
	private final long firstDelayMs;
	private final long maxDelayMs;
	private volatile boolean running;

	/**
	 * @param queue          the topic's queue, shared with the connector
	 * @param lock           the queue's lock, shared by every consumer of the queue
	 * @param pollIntervalMs how long an empty queue is left before it is looked at again
	 */
	public BusAcknowledgingConsumer(IBigQueue queue, Object lock, long pollIntervalMs) {
		this(queue, lock, pollIntervalMs, FIRST_RETRY_DELAY_MILLIS, MAX_RETRY_DELAY_MILLIS);
	}

	BusAcknowledgingConsumer(IBigQueue queue, Object lock, long pollIntervalMs, long firstDelayMs, long maxDelayMs) {
		this.queue = queue;
		this.lock = lock;
		this.pollIntervalMs = pollIntervalMs;
		this.firstDelayMs = firstDelayMs;
		this.maxDelayMs = maxDelayMs;
	}

	/** Without outcomes, every message is safe once handled: removed after it, one by one. */
	@Override
	public void start(Consumer<byte[]> messageHandler) throws ConnectorException {
		startAcknowledged(bytes -> {
			messageHandler.accept(bytes);
			return CompletableFuture.completedFuture(Boolean.TRUE);
		});
	}

	@Override
	public void startAcknowledged(Function<byte[], CompletionStage<Boolean>> messageHandler) throws ConnectorException {
		this.running = true;
		int failures = 0;
		while (this.running) {
			Boolean safe = next(messageHandler);
			if (safe == null) {
				pause(this.pollIntervalMs);
				failures = 0;
			} else if (safe) {
				failures = 0;
			} else {
				failures++;
				pause(Math.min(this.firstDelayMs << Math.min(failures - 1, 10), this.maxDelayMs));
			}
		}
	}

	/**
	 * Takes the head of the queue to the route.
	 *
	 * @return {@code null} when the queue is empty, else whether the head was safe — and removed
	 */
	private Boolean next(Function<byte[], CompletionStage<Boolean>> handler) {
		synchronized (this.lock) {
			try {
				if (this.queue.isEmpty()) {
					return null;
				}
				byte[] head = this.queue.peek();
				BusMessage message;
				try {
					message = BusMessage.fromBytes(head);
				} catch (RuntimeException e) {
					LOG.error("Bus consumer: a message cannot be decoded and is removed — no route can take it", e);
					this.queue.dequeue();
					return Boolean.TRUE;
				}
				if (!safe(handler, message.value())) {
					return Boolean.FALSE;
				}
				this.queue.dequeue();
				return Boolean.TRUE;
			} catch (IOException e) {
				LOG.warn("Bus consumer: the queue could not be read", e);
				return Boolean.FALSE;
			}
		}
	}

	private static boolean safe(Function<byte[], CompletionStage<Boolean>> handler, byte[] value) {
		try {
			CompletionStage<Boolean> stage = handler.apply(value);
			return stage != null && Boolean.TRUE.equals(stage.toCompletableFuture().join());
		} catch (CompletionException | CancellationException e) {
			return false;
		} catch (RuntimeException e) {
			LOG.error("Bus consumer: the route could not take a message", e);
			return false;
		}
	}

	private void pause(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			this.running = false;
		}
	}

	@Override
	public void stop() throws ConnectorException {
		this.running = false;
	}
}
