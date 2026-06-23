package com.garganttua.events.connectors.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.garganttua.core.observability.GlobalObservers;
import com.garganttua.core.observability.IObserver;
import com.garganttua.core.observability.ObservabilityEmitter;
import com.garganttua.core.observability.ObservableEvent;
import com.garganttua.core.observability.ObservableRegistry;
import com.garganttua.core.observability.StartEvent;
import com.garganttua.events.api.exceptions.ConnectorException;

/**
 * Proves the consumer auto-attaches to the global observability firehose with <b>no application
 * registration</b>: events broadcast through the firehose reach the pipeline handler as serialised
 * bytes, and {@link ObservabilityConsumer#stop()} shuts down cleanly. A complementary assertion
 * shows that an event emitted through {@link ObservabilityEmitter} also reaches the firehose (and
 * thus the consumer) once an observer is listening — i.e. the engines feed the same firehose.
 */
class ObservabilityConsumerTest {

	@Test
	void firehoseEventReachesHandlerWithNoAppWiring() throws Exception {
		ObservabilityConsumer consumer = new ObservabilityConsumer(EventFilter.of(null, null));
		AtomicReference<byte[]> received = new AtomicReference<>();
		CountDownLatch gotMessage = new CountDownLatch(1);

		Thread worker = startConsumer(consumer, received, gotMessage);
		settle();

		// No observable exposed to the app: just emit onto the global firehose.
		GlobalObservers.fire(new StartEvent(UUID.randomUUID(), Instant.now(), "workflow:demo:run"));

		assertTrue(gotMessage.await(5, TimeUnit.SECONDS), "handler never received the event");
		byte[] bytes = received.get();
		assertNotNull(bytes);
		String json = new String(bytes, StandardCharsets.UTF_8);
		assertTrue(json.contains("StartEvent"), "json should name the event type: " + json);
		assertTrue(json.contains("workflow:demo:run"), "json should carry the source: " + json);

		consumer.stop();
		worker.join(5_000);
		assertFalse(worker.isAlive(), "consumer thread should stop cleanly");
	}

	@Test
	void emitterEventReachesFirehoseExactlyOnce() throws Exception {
		ObservableRegistry localReg = new ObservableRegistry();
		AtomicInteger firehoseHits = new AtomicInteger();
		String marker = "workflow:once:" + UUID.randomUUID();
		IObserver<ObservableEvent> counter = event -> {
			if (marker.equals(event.source())) {
				firehoseHits.incrementAndGet();
			}
		};
		GlobalObservers.addObserver(counter);
		try {
			// Engines only build/emit events when an observer is registered on their registry;
			// add one to the local registry so the emitter actually fires.
			localReg.addObserver(event -> { });
			try (ObservabilityEmitter.Scope scope =
					ObservabilityEmitter.open(localReg, UUID.randomUUID())) {
				scope.fireStart(marker);
			}
			assertEquals(1, firehoseHits.get(),
					"emitter event should reach the firehose exactly once");
		} finally {
			GlobalObservers.removeObserver(counter);
		}
	}

	private Thread startConsumer(ObservabilityConsumer consumer, AtomicReference<byte[]> received,
			CountDownLatch gotMessage) {
		Thread worker = new Thread(() -> {
			try {
				consumer.start(bytes -> {
					received.compareAndSet(null, bytes);
					gotMessage.countDown();
				});
			} catch (ConnectorException e) {
				fail(e);
			}
		});
		worker.setDaemon(true);
		worker.start();
		return worker;
	}

	private void settle() throws InterruptedException {
		// brief settle so the worker's GlobalObservers.addObserver(...) has run
		TimeUnit.MILLISECONDS.sleep(100);
	}
}
