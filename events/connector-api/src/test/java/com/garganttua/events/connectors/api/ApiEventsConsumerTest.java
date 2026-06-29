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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.service.OperationResponseCode;
import com.garganttua.api.core.filter.Filter;
import com.garganttua.core.observability.EndEvent;
import com.garganttua.core.observability.GlobalObservers;
import com.garganttua.core.reflection.IClass;
import com.garganttua.events.api.exceptions.ConnectorException;

/**
 * Proves the api connector auto-attaches to the global observability firehose with <b>no
 * application registration</b>: an {@code api:operation:*} {@link EndEvent} carrying an
 * {@link com.garganttua.api.commons.event.IEvent} payload, broadcast on the firehose, reaches the
 * pipeline handler as serialised bytes.
 */
class ApiEventsConsumerTest {

	@BeforeAll
	static void installReflection() {
		TestReflection.install();
	}

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

	@Test
	void domainFilterForwardsOnlyTheConfiguredDomain() throws Exception {
		// domain filter set to "contacts": a "newsletters" event with the same operation is dropped.
		ApiEventsConsumer consumer = new ApiEventsConsumer(null, "contacts");
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
		TimeUnit.MILLISECONDS.sleep(100);

		// Same operation key, different domains. Only the contacts event must be forwarded.
		FakeEvent other = new FakeEvent();
		other.setTenantId("newsletter-tenant");
		other.setCode(OperationResponseCode.OK);
		GlobalObservers.fire(new EndEvent(UUID.randomUUID(), Instant.now(),
				"api:operation:newsletters:newsletters-create-oneEntity-newsletter",
				Duration.ofMillis(2), 0, other));

		FakeEvent wanted = new FakeEvent();
		wanted.setTenantId("contact-tenant");
		wanted.setCode(OperationResponseCode.OK);
		GlobalObservers.fire(new EndEvent(UUID.randomUUID(), Instant.now(),
				"api:operation:contacts:contacts-create-oneEntity-contact",
				Duration.ofMillis(2), 0, wanted));

		assertTrue(gotMessage.await(5, TimeUnit.SECONDS), "the contacts event should be forwarded");
		String json = new String(received.get(), StandardCharsets.UTF_8);
		assertTrue(json.contains("contact-tenant"), "must forward the contacts event: " + json);
		assertFalse(json.contains("newsletter-tenant"), "must not forward the newsletters event: " + json);

		consumer.stop();
		worker.join(5_000);
	}

	@Test
	void ifilterForwardsMatchingOperationAndDropsTheRest() throws Exception {
		// The connector's IFilter keeps only create/update/readAll: a delete event is dropped, the
		// following create event is forwarded — proving operation-based filtering end to end.
		Filter filter = Filter.in("operation", "create", "update", "readAll");
		ApiEventsConsumer consumer = new ApiEventsConsumer(null, null, filter);
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
		TimeUnit.MILLISECONDS.sleep(100);

		IClass<?> entity = IClass.getClass(FakeEvent.class);

		FakeEvent deleted = new FakeEvent();
		deleted.setTenantId("delete-tenant");
		deleted.setCode(OperationResponseCode.OK);
		deleted.setOperation(OperationDefinition.deleteOneWithStandardSecurity("contacts", entity));
		GlobalObservers.fire(new EndEvent(UUID.randomUUID(), Instant.now(),
				"api:operation:contacts:delete-one-contact", Duration.ofMillis(2), 0, deleted));

		FakeEvent created = new FakeEvent();
		created.setTenantId("create-tenant");
		created.setCode(OperationResponseCode.OK);
		created.setOperation(OperationDefinition.createOneWithStandardSecurity("contacts", entity));
		GlobalObservers.fire(new EndEvent(UUID.randomUUID(), Instant.now(),
				"api:operation:contacts:create-one-contact", Duration.ofMillis(2), 0, created));

		assertTrue(gotMessage.await(5, TimeUnit.SECONDS), "the create event should be forwarded");
		String json = new String(received.get(), StandardCharsets.UTF_8);
		assertTrue(json.contains("create-tenant"), "must forward the create event: " + json);
		assertFalse(json.contains("delete-tenant"), "must not forward the delete event: " + json);

		consumer.stop();
		worker.join(5_000);
	}

	@Test
	void codecEmitsSelfDescribingRoutingFields() {
		// With no OperationDefinition on the event, the routing fields are present but null — proving
		// the keys exist for downstream transforms while staying null-safe (back-compat).
		ApiEventCodec codec = new ApiEventCodec();
		String json = new String(codec.toBytes(new FakeEvent()), StandardCharsets.UTF_8);
		assertTrue(json.contains("\"domain\""), "payload should carry a domain field: " + json);
		assertTrue(json.contains("\"businessOperation\""),
				"payload should carry a businessOperation field: " + json);
		assertTrue(json.contains("\"useCase\""), "payload should carry a useCase field: " + json);
	}
}
