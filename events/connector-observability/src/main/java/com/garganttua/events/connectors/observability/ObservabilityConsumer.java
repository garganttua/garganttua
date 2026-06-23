package com.garganttua.events.connectors.observability;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import com.garganttua.core.observability.IObservable;
import com.garganttua.core.observability.IObserver;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.observability.ObservableEvent;
import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.exceptions.ConnectorException;

/**
 * Bridges a garganttua {@link IObservable} onto the events pipeline.
 *
 * <p>{@link #start(Consumer)} resolves the observable from {@link ObservableSources} by the
 * configured {@code source} key, attaches an observer that forwards every event passing the
 * {@link EventFilter} (serialised via {@link ObservableEventCodec}) to the pipeline handler, and
 * then blocks the calling daemon thread on a {@link CountDownLatch} until {@link #stop()} detaches
 * the observer and releases the latch.</p>
 */
public final class ObservabilityConsumer implements IConsumer {

	private static final Logger LOG = Logger.getLogger(ObservabilityConsumer.class);

	private final String sourceName;
	private final EventFilter filter;
	private final ObservableEventCodec codec;
	private final CountDownLatch stopLatch = new CountDownLatch(1);

	private volatile IObservable observable;
	private volatile IObserver<ObservableEvent> observer;

	/**
	 * @param sourceName the {@link ObservableSources} registry key of the observable to observe
	 * @param filter     the event-type / source filter to apply
	 */
	public ObservabilityConsumer(String sourceName, EventFilter filter) {
		this.sourceName = Objects.requireNonNull(sourceName, "source name cannot be null");
		this.filter = Objects.requireNonNull(filter, "filter cannot be null");
		this.codec = new ObservableEventCodec();
	}

	@Override
	public void start(Consumer<byte[]> messageHandler) throws ConnectorException {
		Objects.requireNonNull(messageHandler, "message handler cannot be null");
		this.observable = ObservableSources.lookup(this.sourceName)
				.orElseThrow(() -> new ConnectorException(
						"no observable registered under source '" + this.sourceName + "'"));
		this.observer = event -> forward(event, messageHandler);
		this.observable.addObserver(this.observer);
		LOG.debug("Observability consumer attached to source {}", this.sourceName);
		awaitStop();
	}

	private void forward(ObservableEvent event, Consumer<byte[]> messageHandler) {
		if (event != null && this.filter.matches(event)) {
			messageHandler.accept(this.codec.toBytes(event));
		}
	}

	private void awaitStop() throws ConnectorException {
		try {
			this.stopLatch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ConnectorException("observability consumer interrupted", e);
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
		LOG.debug("Observability consumer detached from source {}", this.sourceName);
	}
}
