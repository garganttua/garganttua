package com.garganttua.events.connectors.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.service.OperationResponseCode;
import com.garganttua.core.observability.EndEvent;
import com.garganttua.core.observability.ObservableRegistry;
import com.garganttua.events.api.exceptions.ConnectorException;
import com.garganttua.events.connectors.observability.ObservableSources;

/**
 * Proves the api connector bridge: an {@code api:operation:*} {@link EndEvent} carrying an
 * {@link com.garganttua.api.commons.event.IEvent} payload reaches the pipeline handler as bytes.
 */
class ApiEventsConsumerTest {

	@Test
	void endEventWithBusinessPayloadReachesHandlerAsBytes() throws Exception {
		String sourceKey = "api-test-" + UUID.randomUUID();
		ObservableRegistry registry = new ObservableRegistry();
		ObservableSources.register(sourceKey, registry);
		try {
			ApiEventsConsumer consumer = new ApiEventsConsumer(sourceKey, null);
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

			FakeEvent business = new FakeEvent();
			business.setTenantId("tenant-42");
			business.setUserId("user-7");
			business.setCode(OperationResponseCode.OK);
			business.setOut("hello");
			registry.fire(new EndEvent(UUID.randomUUID(), Instant.now(),
					"api:operation:users:READ_ALL", Duration.ofMillis(3), 0, business));

			assertTrue(gotMessage.await(5, TimeUnit.SECONDS), "handler never received the event");
			byte[] bytes = received.get();
			assertNotNull(bytes);
			String json = new String(bytes, StandardCharsets.UTF_8);
			assertTrue(json.contains("tenant-42"), "json should carry the tenant: " + json);
			assertTrue(json.contains("OK"), "json should carry the response code: " + json);

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
