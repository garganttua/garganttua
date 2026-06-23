package com.garganttua.events.connectors.observability;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.garganttua.core.observability.ObservableRegistry;
import com.garganttua.core.observability.StartEvent;
import com.garganttua.events.api.exceptions.ConnectorException;

/**
 * Proves the observer -> bytes bridge: a {@link StartEvent} fired on a registered observable
 * reaches the pipeline message handler as serialised bytes.
 */
class ObservabilityConsumerTest {

	@Test
	void firedEventReachesHandlerAsBytes() throws Exception {
		String sourceKey = "obs-test-" + UUID.randomUUID();
		ObservableRegistry registry = new ObservableRegistry();
		ObservableSources.register(sourceKey, registry);
		try {
			ObservabilityConsumer consumer =
					new ObservabilityConsumer(sourceKey, EventFilter.of(null, null));
			AtomicReference<byte[]> received = new AtomicReference<>();
			CountDownLatch gotMessage = new CountDownLatch(1);

			Thread worker = new Thread(() -> {
				try {
					consumer.start(bytes -> {
						received.set(bytes);
						gotMessage.countDown();
					});
				} catch (ConnectorException e) {
					fail(e);
				}
			});
			worker.setDaemon(true);
			worker.start();

			waitForAttachment(registry);
			registry.fire(new StartEvent(UUID.randomUUID(), Instant.now(), "workflow:demo:run"));

			assertTrue(gotMessage.await(5, TimeUnit.SECONDS), "handler never received the event");
			byte[] bytes = received.get();
			assertNotNull(bytes);
			String json = new String(bytes, StandardCharsets.UTF_8);
			assertTrue(json.contains("StartEvent"), "json should name the event type: " + json);
			assertTrue(json.contains("workflow:demo:run"), "json should carry the source: " + json);

			consumer.stop();
		} finally {
			ObservableSources.unregister(sourceKey);
		}
	}

	private void waitForAttachment(ObservableRegistry registry) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!registry.hasObservers() && System.nanoTime() < deadline) {
			TimeUnit.MILLISECONDS.sleep(5);
		}
		assertTrue(registry.hasObservers(), "consumer never attached an observer");
	}
}
