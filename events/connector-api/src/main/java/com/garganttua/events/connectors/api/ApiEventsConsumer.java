package com.garganttua.events.connectors.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import com.garganttua.api.commons.event.IEvent;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.core.observability.EndEvent;
import com.garganttua.core.observability.ErrorEvent;
import com.garganttua.core.observability.GlobalObservers;
import com.garganttua.core.observability.IObserver;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.observability.ObservableEvent;
import com.garganttua.events.api.IAcknowledgingConsumer;
import com.garganttua.events.api.exceptions.ConnectorException;
import com.garganttua.events.connectors.api.ApiEventsSettings.Delivery;
import com.garganttua.events.connectors.api.ApiEventsSettings.Overflow;

/**
 * Forwards api business {@link IEvent}s into the events pipeline with <b>zero application
 * wiring</b>.
 *
 * <p>On start it registers an {@link IObserver} with {@link GlobalObservers}, so every event emitted by an
 * api {@code Domain} reaches it. It keeps only the terminal {@link EndEvent}/{@link ErrorEvent} whose source
 * matches {@code api:operation:*} (and, when configured, a domain and an operation), and — when the connector
 * carries an application {@link IFilter} — only those satisfying it ({@link ApiEventFilter}).</p>
 *
 * <p><b>Numbered.</b> Every event it keeps takes, there and then — before any queue — the next number of its
 * run: {@code bootId} names the run (drawn at start), {@code seq} counts from 1 without gap. An event lost
 * afterwards (a full queue, a killed process) is therefore a jump downstream. On a clean stop the run ends
 * with an {@code endOfSequence} message carrying the last number: a run whose end never arrives ended
 * abruptly. The event's own identity and its source node are the journey's ({@code JourneyStep}), set by the
 * envelope, not here.</p>
 *
 * <p><b>Delivery</b> ({@link ApiEventsSettings}):</p>
 * <ul>
 * <li>{@code async} (default) — the emitting thread serialises and queues; the consumer's own thread (the
 * caller of start) drains the bounded queue into the route. A full queue drops the event ({@code drop}, the
 * default) or makes the emitting thread wait a bounded time ({@code block}); a dropped event is counted. On
 * stop, the queue is drained before the run ends.</li>
 * <li>{@code sync} — no queue: the observer is BLOCKING ({@link GlobalObservers#addBlockingObserver}), the
 * route runs while the operation waits, and a route that fails — or does not answer in time — fails the
 * operation. It needs the route's outcome, so the connector must be started with acknowledgement.</li>
 * </ul>
 */
public final class ApiEventsConsumer implements IAcknowledgingConsumer {

	private static final Logger LOG = Logger.getLogger(ApiEventsConsumer.class);
	private static final String SOURCE_PREFIX = "api:operation:";
	private static final long POLL_MILLIS = 200L;
	private static final long OVERFLOW_LOG_INTERVAL_MILLIS = 5_000L;
	/** How long a stop waits for events already being queued, then for room for the end of the run. */
	private static final long STOP_GRACE_MILLIS = 5_000L;

	private final String operation;
	private final String domain;
	private final IFilter filter;
	private final ApiEventCodec codec;
	private final ApiEventsSettings settings;
	private final AtomicLong dropped;
	private final BlockingQueue<byte[]> queue;
	private final AtomicLong seq = new AtomicLong();
	/** Emitting threads between "kept an event" and "queued it": a stop waits for them. */
	private final AtomicInteger inFlight = new AtomicInteger();
	private final CountDownLatch stopped = new CountDownLatch(1);

	private volatile boolean closed;
	private volatile String bootId;
	private volatile Function<byte[], CompletionStage<Boolean>> route;
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
		this(operation, domain, null);
	}

	/**
	 * @param operation optional operation-key filter; {@code null}/blank matches every operation
	 * @param domain    optional domain filter; {@code null}/blank matches every domain
	 * @param filter    optional application {@link IFilter}; {@code null} forwards every event that
	 *                  already passes the source filtering ({@link ApiEventFilter})
	 */
	public ApiEventsConsumer(String operation, String domain, IFilter filter) {
		this(operation, domain, filter, ApiEventsSettings.DEFAULTS, new AtomicLong());
	}

	/**
	 * @param operation optional operation-key filter; {@code null}/blank matches every operation
	 * @param domain    optional domain filter; {@code null}/blank matches every domain
	 * @param filter    optional application {@link IFilter}
	 * @param settings  how events are delivered
	 * @param dropped   the counter of dropped events this consumer adds to — the connector's
	 */
	public ApiEventsConsumer(String operation, String domain, IFilter filter, ApiEventsSettings settings,
			AtomicLong dropped) {
		this.operation = normalize(operation);
		this.domain = normalize(domain);
		// Defensive copy: the filter tree is mutable; snapshot it so the consumer's matching is
		// stable for its whole lifetime.
		this.filter = ApiEventFilter.snapshot(filter);
		this.codec = new ApiEventCodec();
		this.settings = Objects.requireNonNull(settings, "settings cannot be null");
		this.dropped = Objects.requireNonNull(dropped, "dropped counter cannot be null");
		this.queue = settings.delivery() == Delivery.ASYNC ? new ArrayBlockingQueue<>(settings.queueCapacity()) : null;
	}

	private static String normalize(String operation) {
		return Optional.ofNullable(operation)
				.map(String::trim)
				.filter(value -> !value.isEmpty())
				.orElse("");
	}

	/**
	 * Starts without the route's outcome: {@code async} only — a {@code sync} delivery exists to fail the
	 * operation when its route fails, and here nothing says it did.
	 */
	@Override
	public void start(Consumer<byte[]> messageHandler) throws ConnectorException {
		Objects.requireNonNull(messageHandler, "message handler cannot be null");
		if (this.settings.delivery() == Delivery.SYNC) {
			throw new ConnectorException("api connector: 'delivery' is 'sync', which fails an operation whose route "
					+ "fails — it needs the route's outcome, and this route is started without it "
					+ "(quality of service AT_MOST_ONCE)");
		}
		run(bytes -> {
			messageHandler.accept(bytes);
			return null;
		});
	}

	@Override
	public void startAcknowledged(Function<byte[], CompletionStage<Boolean>> messageHandler) throws ConnectorException {
		run(Objects.requireNonNull(messageHandler, "message handler cannot be null"));
	}

	private void run(Function<byte[], CompletionStage<Boolean>> handler) throws ConnectorException {
		this.route = handler;
		this.bootId = UUID.randomUUID().toString();
		if (this.settings.delivery() == Delivery.SYNC) {
			this.observer = this::deliverNow;
			GlobalObservers.addBlockingObserver(this.observer);
			LOG.debug("Api events consumer attached to the global firehose, synchronous (run {})", this.bootId);
			awaitStop();
			return;
		}
		this.observer = this::enqueue;
		GlobalObservers.addObserver(this.observer);
		LOG.debug("Api events consumer attached to the global firehose (run {})", this.bootId);
		drain(handler);
	}

	private void enqueue(ObservableEvent event) {
		businessEvent(event).ifPresent(business -> {
			this.inFlight.incrementAndGet();
			try {
				if (this.closed) {
					return;
				}
				long n = this.seq.incrementAndGet();
				if (!offer(this.codec.toBytes(business, this.bootId, n))) {
					overflow(n);
				}
			} finally {
				this.inFlight.decrementAndGet();
			}
		});
	}

	private boolean offer(byte[] bytes) {
		if (this.settings.overflow() == Overflow.DROP) {
			return this.queue.offer(bytes);
		}
		try {
			return this.queue.offer(bytes, this.settings.overflowTimeoutMs(), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private void overflow(long n) {
		long total = this.dropped.incrementAndGet();
		long now = System.currentTimeMillis();
		if (now - this.lastOverflowLog >= OVERFLOW_LOG_INTERVAL_MILLIS) {
			this.lastOverflowLog = now;
			LOG.warn("Api events consumer queue full ({}): event {} of run {} dropped, {} dropped in all",
					this.settings.queueCapacity(), n, this.bootId, total);
		}
	}

	/** {@code sync}: runs the route now and fails — hence fails the operation — unless it succeeded. */
	private void deliverNow(ObservableEvent event) {
		businessEvent(event).ifPresent(business -> {
			long n = this.seq.incrementAndGet();
			if (!awaitRoute(this.codec.toBytes(business, this.bootId, n))) {
				throw new IllegalStateException("the route of event " + n + " of run " + this.bootId + " failed");
			}
		});
	}

	private boolean awaitRoute(byte[] bytes) {
		CompletionStage<Boolean> outcome = this.route.apply(bytes);
		try {
			return Boolean.TRUE.equals(outcome.toCompletableFuture()
					.get(this.settings.deliveryTimeoutMs(), TimeUnit.MILLISECONDS));
		} catch (TimeoutException e) {
			throw new IllegalStateException("the route did not answer within " + this.settings.deliveryTimeoutMs()
					+ " ms", e);
		} catch (ExecutionException e) {
			throw new IllegalStateException("the route failed: " + e.getCause(), e.getCause());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for the route", e);
		}
	}

	private Optional<IEvent> businessEvent(ObservableEvent event) {
		if (event == null || !isTerminal(event) || !sourceMatches(event)) {
			return Optional.empty();
		}
		if (!(event.payload() instanceof IEvent business)) {
			return Optional.empty();
		}
		return ApiEventFilter.matches(this.filter, business) ? Optional.of(business) : Optional.empty();
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

	/** Drains the queue into the route until a stop has closed it AND it is empty — the end of the run last. */
	private void drain(Function<byte[], CompletionStage<Boolean>> handler) throws ConnectorException {
		try {
			while (true) {
				byte[] bytes = this.queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
				if (bytes != null) {
					handler.apply(bytes);
				} else if (this.closed) {
					return;
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ConnectorException("api events consumer interrupted", e);
		}
	}

	private void awaitStop() throws ConnectorException {
		try {
			this.stopped.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ConnectorException("api events consumer interrupted", e);
		}
	}

	/**
	 * Stops taking events, waits for those already being queued, then ends the run: {@code endOfSequence} is
	 * queued behind them ({@code async}, drained before the consumer's thread returns) or delivered now
	 * ({@code sync}).
	 */
	@Override
	public void stop() throws ConnectorException {
		this.closed = true;
		IObserver<ObservableEvent> obv = this.observer;
		if (obv != null) {
			GlobalObservers.removeObserver(obv);
		}
		try {
			if (this.route != null) {
				endRun();
			}
		} finally {
			this.stopped.countDown();
		}
		LOG.debug("Api events consumer detached from the global firehose (run {} ended at {})", this.bootId,
				this.seq.get());
	}

	private void endRun() throws ConnectorException {
		long deadline = System.currentTimeMillis() + STOP_GRACE_MILLIS;
		while (this.inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
			Thread.onSpinWait();
		}
		byte[] end = this.codec.endOfSequence(this.bootId, this.seq.get());
		if (this.queue == null) {
			try {
				if (!awaitRoute(end)) {
					LOG.warn("Api events consumer: the end of run {} could not be delivered", this.bootId);
				}
			} catch (IllegalStateException e) {
				LOG.warn("Api events consumer: the end of run {} could not be delivered: {}", this.bootId,
						e.getMessage());
			}
			return;
		}
		try {
			if (!this.queue.offer(end, STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
				LOG.warn("Api events consumer: no room for the end of run {} — downstream will read it as abrupt",
						this.bootId);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ConnectorException("api events consumer interrupted while ending its run", e);
		}
	}
}
