package com.garganttua.events.connectors.api;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.garganttua.api.commons.event.IEvent;
import com.garganttua.core.observability.EndEvent;
import com.garganttua.core.observability.ErrorEvent;
import com.garganttua.core.observability.GlobalObservers;
import com.garganttua.core.observability.IObserver;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.observability.ObservableEvent;
import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.exceptions.ConnectorException;

/**
 * Forwards api business {@link IEvent}s into the events pipeline with <b>zero application
 * wiring</b>.
 *
 * <p>On {@link #start(Consumer)} it registers an {@link IObserver} with {@link GlobalObservers},
 * so every event emitted by an api {@code Domain} reaches it. It keeps only the terminal
 * {@link EndEvent}/{@link ErrorEvent} whose source matches {@code api:operation:*} (and, when
 * configured, a specific operation suffix), extracts the {@link IEvent} payload, serialises it via
 * {@link ApiEventCodec} and <b>enqueues</b> the bytes — a cheap, non-blocking step that runs on
 * arbitrary emitting threads. The consumer's own daemon thread (the caller of {@code start})
 * drains the bounded queue and pushes the bytes into the pipeline handler, decoupling the
 * instrumented application from pipeline latency.</p>
 *
 * <p>On queue overflow the event is dropped with a single throttled warn — the emitting thread is
 * never blocked.</p>
 */
public final class ApiEventsConsumer implements IConsumer {

	private static final Logger LOG = Logger.getLogger(ApiEventsConsumer.class);
	private static final String SOURCE_PREFIX = "api:operation:";
	private static final int QUEUE_CAPACITY = 10_000;
	private static final long POLL_MILLIS = 200L;
	private static final long OVERFLOW_LOG_INTERVAL_MILLIS = 5_000L;

	private final String operation;
	private final String domain;
	private final ApiEventCodec codec;
	private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

	private volatile boolean running;
	private volatile IObserver<ObservableEvent> observer;
	private volatile long lastOverflowLog;

	/**
	 * @param operation optional operation name filter; {@code null}/blank matches every operation
	 */
	public ApiEventsConsumer(String operation) {
		this(operation, null);
	}

	/**
	 * @param operation optional operation-key filter; {@code null}/blank matches every operation
	 * @param domain    optional domain filter; {@code null}/blank matches every domain
	 */
	public ApiEventsConsumer(String operation, String domain) {
		this.operation = normalize(operation);
		this.domain = normalize(domain);
		this.codec = new ApiEventCodec();
	}

	private static String normalize(String operation) {
		return Optional.ofNullable(operation)
				.map(String::trim)
				.filter(value -> !value.isEmpty())
				.orElse("");
	}

	@Override
	public void start(Consumer<byte[]> messageHandler) throws ConnectorException {
		Objects.requireNonNull(messageHandler, "message handler cannot be null");
		this.running = true;
		this.observer = this::enqueue;
		GlobalObservers.addObserver(this.observer);
		LOG.debug("Api events consumer attached to the global firehose");
		drain(messageHandler);
	}

	private void enqueue(ObservableEvent event) {
		businessEvent(event).ifPresent(business -> {
			if (!this.queue.offer(this.codec.toBytes(business))) {
				logOverflow();
			}
		});
	}

	private void logOverflow() {
		long now = System.currentTimeMillis();
		if (now - this.lastOverflowLog >= OVERFLOW_LOG_INTERVAL_MILLIS) {
			this.lastOverflowLog = now;
			LOG.warn("Api events consumer queue full ({}); dropping events", QUEUE_CAPACITY);
		}
	}

	private Optional<IEvent> businessEvent(ObservableEvent event) {
		if (event == null || !isTerminal(event) || !sourceMatches(event)) {
			return Optional.empty();
		}
		return event.payload() instanceof IEvent business ? Optional.of(business) : Optional.empty();
	}

	private boolean isTerminal(ObservableEvent event) {
		return event instanceof EndEvent || event instanceof ErrorEvent;
	}

	private boolean sourceMatches(ObservableEvent event) {
		String source = event.source();
		if (source == null || !source.startsWith(SOURCE_PREFIX)) {
			return false;
		}
		// Source is api:operation:<domain>:<operationKey>; neither segment contains a colon.
		String remainder = source.substring(SOURCE_PREFIX.length());
		int separator = remainder.indexOf(':');
		String domainSegment = separator >= 0 ? remainder.substring(0, separator) : remainder;
		String operationKey = separator >= 0 ? remainder.substring(separator + 1) : "";
		if (!this.domain.isEmpty() && !this.domain.equals(domainSegment)) {
			return false;
		}
		return this.operation.isEmpty() || operationKey.equals(this.operation)
				|| source.endsWith(":" + this.operation);
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
			throw new ConnectorException("api events consumer interrupted", e);
		}
	}

	@Override
	public void stop() throws ConnectorException {
		this.running = false;
		IObserver<ObservableEvent> obv = this.observer;
		if (obv != null) {
			GlobalObservers.removeObserver(obv);
		}
		LOG.debug("Api events consumer detached from the global firehose");
	}
}
