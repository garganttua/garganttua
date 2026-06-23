package com.garganttua.events.connectors.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.garganttua.core.observability.GlobalObservers;
import com.garganttua.events.api.exceptions.ConnectorException;

/**
 * Proves the api connector auto-attaches to the global observability firehose with <b>no
 * application registration</b>: an {@code api:operation:*} {@link EndEvent} carrying an
 * {@link com.garganttua.api.commons.event.IEvent} payload, broadcast on the firehose, reaches the
 * pipeline handler as serialised bytes.
 */
class ApiEventsConsumerTest {

	@Test
	void endEventWithBusinessPayloadReachesHandlerWithNoAppWiring() throws Exception {
		ApiEventsConsumer consumer = new ApiEventsConsumer(null);
		AtomicReference<byte[]> received = new AtomicReference<>();
		CountDownLatch gotMessage = new CountDownLatch(1);

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

		// brief settle so the worker's GlobalObservers.addObserver(...) has run
		TimeUnit.MILLISECONDS.sleep(100);

		FakeEvent business = new FakeEvent();
		business.setTenantId("tenant-42");
		business.setUserId("user-7");
		business.setCode(OperationResponseCode.OK);
		business.setOut("hello");
		GlobalObservers.fire(new EndEvent(UUID.randomUUID(), Instant.now(),
				"api:operation:users:READ_ALL", Duration.ofMillis(3), 0, business));

		assertTrue(gotMessage.await(5, TimeUnit.SECONDS), "handler never received the event");
		byte[] bytes = received.get();
		assertNotNull(bytes);
		String json = new String(bytes, StandardCharsets.UTF_8);
		assertTrue(json.contains("tenant-42"), "json should carry the tenant: " + json);
		assertTrue(json.contains("OK"), "json should carry the response code: " + json);

		consumer.stop();
		worker.join(5_000);
		assertFalse(worker.isAlive(), "consumer thread should stop cleanly");
	}
}
