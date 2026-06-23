package com.garganttua.events.connectors.api;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import com.garganttua.api.commons.event.IEvent;
import com.garganttua.core.observability.EndEvent;
import com.garganttua.core.observability.ErrorEvent;
import com.garganttua.core.observability.IObservable;
import com.garganttua.core.observability.IObserver;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.observability.ObservableEvent;
import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.exceptions.ConnectorException;
import com.garganttua.events.connectors.observability.ObservableSources;

/**
 * Observes an api {@code Domain} (registered as an {@link IObservable} in
 * {@link ObservableSources}) and forwards its business {@link IEvent}s into the events pipeline.
 *
 * <p>It keeps only the terminal {@link EndEvent}/{@link ErrorEvent} whose source matches
 * {@code api:operation:*} and, when configured, a specific operation suffix; it extracts the
 * {@link IEvent} payload, serialises it via {@link ApiEventCodec} and hands the bytes to the
 * pipeline. {@link #start(Consumer)} blocks the calling daemon thread until {@link #stop()}.</p>
 */
public final class ApiEventsConsumer implements IConsumer {

	private static final Logger LOG = Logger.getLogger(ApiEventsConsumer.class);
	private static final String SOURCE_PREFIX = "api:operation:";

	private final String sourceName;
	private final String operation;
	private final ApiEventCodec codec;
	private final CountDownLatch stopLatch = new CountDownLatch(1);

	private volatile IObservable observable;
	private volatile IObserver<ObservableEvent> observer;

	/**
	 * @param sourceName the {@link ObservableSources} registry key of the Domain to observe
	 * @param operation  optional operation name filter; {@code null}/blank matches every operation
	 */
	public ApiEventsConsumer(String sourceName, String operation) {
		this.sourceName = Objects.requireNonNull(sourceName, "source name cannot be null");
		this.operation = normalize(operation);
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
		this.observable = ObservableSources.lookup(this.sourceName)
				.orElseThrow(() -> new ConnectorException(
						"no observable registered under source '" + this.sourceName + "'"));
		this.observer = event -> forward(event, messageHandler);
		this.observable.addObserver(this.observer);
		LOG.debug("Api events consumer attached to source {}", this.sourceName);
		awaitStop();
	}

	private void forward(ObservableEvent event, Consumer<byte[]> messageHandler) {
		businessEvent(event).ifPresent(business -> messageHandler.accept(this.codec.toBytes(business)));
	}

	private Optional<IEvent> businessEvent(ObservableEvent event) {
		if (!isTerminal(event) || !sourceMatches(event)) {
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
		return this.operation.isEmpty() || source.endsWith(":" + this.operation);
	}

	private void awaitStop() throws ConnectorException {
		try {
			this.stopLatch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ConnectorException("api events consumer interrupted", e);
		}
	}

	@Override
	public void stop() throws ConnectorException {
		IObservable obs = this.observable;
		IObserver<ObservableEvent> obv = this.observer;
		if (obs != null && obv != null) {
			obs.removeObserver(obv);
		}
		this.stopLatch.countDown();
		LOG.debug("Api events consumer detached from source {}", this.sourceName);
	}
}
