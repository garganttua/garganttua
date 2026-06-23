package com.garganttua.events.connectors.observability;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.garganttua.core.observability.GlobalObservers;
import com.garganttua.core.observability.IObserver;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.observability.ObservableEvent;
import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.exceptions.ConnectorException;

/**
 * Bridges the garganttua observability firehose onto the events pipeline with <b>zero application
 * wiring</b>.
 *
 * <p>On {@link #start(Consumer)} it registers an {@link IObserver} with {@link GlobalObservers}, so
 * every event emitted anywhere in the platform reaches it. The observer is intentionally cheap: for
 * each event passing the configured {@link EventFilter} it serialises the bytes and <b>enqueues</b>
 * them onto a bounded queue, then returns — it runs on arbitrary emitting threads and must never
 * block them or be slowed by pipeline latency. The consumer's own daemon thread (the caller of
 * {@code start}) drains that queue and pushes the bytes into the pipeline handler. This decouples
 * the instrumented application from the pipeline.</p>
 *
 * <p>On queue overflow the event is dropped with a single throttled warn — the emitting thread is
 * never blocked.</p>
 */
public final class ObservabilityConsumer implements IConsumer {

	private static final Logger LOG = Logger.getLogger(ObservabilityConsumer.class);
	private static final int QUEUE_CAPACITY = 10_000;
	private static final long POLL_MILLIS = 200L;
	private static final long OVERFLOW_LOG_INTERVAL_MILLIS = 5_000L;

	private final EventFilter filter;
	private final ObservableEventCodec codec;
	private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

	private volatile boolean running;
	private volatile IObserver<ObservableEvent> observer;
	private volatile long lastOverflowLog;

	/**
	 * @param filter the event-type / source filter to apply before forwarding
	 */
	public ObservabilityConsumer(EventFilter filter) {
		this.filter = Objects.requireNonNull(filter, "filter cannot be null");
		this.codec = new ObservableEventCodec();
	}

	@Override
	public void start(Consumer<byte[]> messageHandler) throws ConnectorException {
		Objects.requireNonNull(messageHandler, "message handler cannot be null");
		this.running = true;
		this.observer = this::enqueue;
		GlobalObservers.addObserver(this.observer);
		LOG.debug("Observability consumer attached to the global firehose");
		drain(messageHandler);
	}

	private void enqueue(ObservableEvent event) {
		if (event == null || !this.filter.matches(event)) {
			return;
		}
		if (!this.queue.offer(this.codec.toBytes(event))) {
			logOverflow();
		}
	}

	private void logOverflow() {
		long now = System.currentTimeMillis();
		if (now - this.lastOverflowLog >= OVERFLOW_LOG_INTERVAL_MILLIS) {
			this.lastOverflowLog = now;
			LOG.warn("Observability consumer queue full ({}); dropping events", QUEUE_CAPACITY);
		}
	}

	private void drain(Consumer<byte[]> messageHandler) throws ConnectorException {
		try {
			while (this.running) {
				byte[] bytes = this.queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
				if (bytes != null) {
					messageHandler.accept(bytes);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ConnectorException("observability consumer interrupted", e);
		}
	}

	@Override
	public void stop() throws ConnectorException {
		this.running = false;
		IObserver<ObservableEvent> obv = this.observer;
		if (obv != null) {
			GlobalObservers.removeObserver(obv);
		}
		LOG.debug("Observability consumer detached from the global firehose");
	}
}
