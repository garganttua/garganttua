package com.garganttua.events.connectors.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.garganttua.api.commons.event.RequestOrigin;
import com.garganttua.api.commons.service.OperationResponseCode;
import com.garganttua.core.observability.EndEvent;
import com.garganttua.core.observability.GlobalObservers;
import com.garganttua.core.observability.ObserverFailureException;
import com.garganttua.events.api.exceptions.ConnectorException;
import com.garganttua.events.connectors.api.ApiEventsSettings.Delivery;
import com.garganttua.events.connectors.api.ApiEventsSettings.Overflow;

/**
 * What the api connector promises an access log: every event it keeps is numbered before it can be lost, a
 * clean stop drains the queue and ends the run, a full queue drops — counted — or waits, and a synchronous
 * connector fails the operation whose route fails.
 */
@DisplayName("api connector — numbering, overflow, synchronous delivery")
class ApiEventsDeliveryTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@BeforeAll
	static void installReflection() {
		TestReflection.install();
	}

	private final List<ApiEventsConsumer> started = new ArrayList<>();

	@AfterEach
	void stopAll() throws ConnectorException {
		for (ApiEventsConsumer consumer : this.started) {
			consumer.stop();
		}
	}

	private static void fire(String tenant) {
		FakeEvent business = new FakeEvent();
		business.setTenantId(tenant);
		business.setCode(OperationResponseCode.OK);
		business.setOrigin(new RequestOrigin("198.51.100.20", "Mozilla/5.0", "authz-1"));
		GlobalObservers.fire(new EndEvent(UUID.randomUUID(), Instant.now(), "api:operation:users:READ_ALL",
				Duration.ofMillis(1), 0, business));
	}

	private static JsonNode json(byte[] bytes) {
		try {
			return JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	/** Starts a consumer on its own thread, as the engine does, and waits until it listens. */
	private Thread start(ApiEventsConsumer consumer, Function<byte[], CompletionStage<Boolean>> route)
			throws InterruptedException {
		this.started.add(consumer);
		Thread thread = new Thread(() -> {
			try {
				consumer.startAcknowledged(route);
			} catch (ConnectorException e) {
				throw new IllegalStateException(e);
			}
		});
		thread.setDaemon(true);
		thread.start();
		TimeUnit.MILLISECONDS.sleep(100);
		return thread;
	}

	private static ApiEventsConsumer consumer(ApiEventsSettings settings, AtomicLong dropped) {
		return new ApiEventsConsumer(null, null, null, settings, dropped);
	}

	@Nested
	@DisplayName("numbering")
	class Numbering {

		@Test
		@DisplayName("events of a run count from 1, the queue is drained on stop, the end of the run comes last")
		void run() throws Exception {
			List<byte[]> received = Collections.synchronizedList(new ArrayList<>());
			ApiEventsConsumer consumer = consumer(ApiEventsSettings.DEFAULTS, new AtomicLong());
			Thread thread = start(consumer, bytes -> {
				received.add(bytes);
				return CompletableFuture.completedFuture(true);
			});

			fire("t1");
			fire("t2");
			fire("t3");
			consumer.stop();
			thread.join(5_000);

			assertFalse(thread.isAlive(), "the consumer's thread returns once its run ended");
			assertEquals(4, received.size(), "three events, then the end of the run");
			String bootId = json(received.get(0)).get("bootId").asText();
			for (int i = 0; i < 3; i++) {
				JsonNode event = json(received.get(i));
				assertEquals(bootId, event.get("bootId").asText());
				assertEquals(i + 1, event.get("seq").asLong());
			}
			JsonNode end = json(received.get(3));
			assertEquals("endOfSequence", end.get("type").asText());
			assertEquals(bootId, end.get("bootId").asText());
			assertEquals(3, end.get("lastSeq").asLong());
		}

		@Test
		@DisplayName("each run has its own bootId")
		void runs() throws Exception {
			List<String> boots = Collections.synchronizedList(new ArrayList<>());
			for (int run = 0; run < 2; run++) {
				CountDownLatch got = new CountDownLatch(1);
				ApiEventsConsumer consumer = consumer(ApiEventsSettings.DEFAULTS, new AtomicLong());
				start(consumer, bytes -> {
					JsonNode node = json(bytes);
					if (node.has("seq")) {
						boots.add(node.get("bootId").asText());
						got.countDown();
					}
					return CompletableFuture.completedFuture(true);
				});
				fire("t");
				assertTrue(got.await(5, TimeUnit.SECONDS));
				consumer.stop();
			}
			assertEquals(2, boots.size());
			assertFalse(boots.get(0).equals(boots.get(1)), "a new run, a new bootId");
		}

		@Test
		@DisplayName("the origin of the operation travels with it")
		void origin() throws Exception {
			CountDownLatch got = new CountDownLatch(1);
			List<byte[]> received = Collections.synchronizedList(new ArrayList<>());
			start(consumer(ApiEventsSettings.DEFAULTS, new AtomicLong()), bytes -> {
				received.add(bytes);
				got.countDown();
				return CompletableFuture.completedFuture(true);
			});

			fire("t");

			assertTrue(got.await(5, TimeUnit.SECONDS));
			JsonNode origin = json(received.get(0)).get("origin");
			assertEquals("198.51.100.20", origin.get("address").asText());
			assertEquals("Mozilla/5.0", origin.get("userAgent").asText());
			assertEquals("authz-1", origin.get("sessionId").asText());
		}
	}

	@Nested
	@DisplayName("a full queue")
	class Overflowing {

		@Test
		@DisplayName("drop: the event is dropped and counted, but keeps its number — the gap shows downstream")
		void drop() throws Exception {
			CountDownLatch release = new CountDownLatch(1);
			List<byte[]> received = Collections.synchronizedList(new ArrayList<>());
			AtomicLong dropped = new AtomicLong();
			ApiEventsConsumer consumer = consumer(new ApiEventsSettings(Delivery.ASYNC, 5_000, Overflow.DROP, 1_000, 1),
					dropped);
			Thread thread = start(consumer, bytes -> {
				received.add(bytes);
				awaitQuietly(release);
				return CompletableFuture.completedFuture(true);
			});

			fire("held by the route");
			TimeUnit.MILLISECONDS.sleep(100);
			fire("queued");
			fire("dropped 1");
			fire("dropped 2");
			release.countDown();
			consumer.stop();
			thread.join(5_000);

			assertEquals(2, dropped.get());
			assertEquals(List.of(1L, 2L), received.stream().map(b -> json(b)).filter(n -> n.has("seq"))
					.map(n -> n.get("seq").asLong()).toList());
			assertEquals(4, json(received.get(received.size() - 1)).get("lastSeq").asLong(),
					"the run says 4 were taken: 3 and 4 are known lost");
		}

		@Test
		@DisplayName("block: the emitting thread waits for room, and nothing is lost")
		void block() throws Exception {
			CountDownLatch release = new CountDownLatch(1);
			List<byte[]> received = Collections.synchronizedList(new ArrayList<>());
			AtomicLong dropped = new AtomicLong();
			ApiEventsConsumer consumer = consumer(new ApiEventsSettings(Delivery.ASYNC, 5_000, Overflow.BLOCK, 5_000, 1),
					dropped);
			Thread thread = start(consumer, bytes -> {
				received.add(bytes);
				awaitQuietly(release);
				return CompletableFuture.completedFuture(true);
			});
			new Thread(() -> {
				sleepQuietly(300);
				release.countDown();
			}).start();

			fire("held by the route");
			TimeUnit.MILLISECONDS.sleep(100);
			fire("queued");
			long before = System.nanoTime();
			fire("waits for room");
			assertTrue(System.nanoTime() - before > TimeUnit.MILLISECONDS.toNanos(100), "the emitter waited");
			consumer.stop();
			thread.join(5_000);

			assertEquals(0, dropped.get());
			assertEquals(4, received.size(), "three events and the end of the run");
		}

		@Test
		@DisplayName("block, past its timeout: dropped, counted")
		void blockTimeout() throws Exception {
			CountDownLatch release = new CountDownLatch(1);
			AtomicLong dropped = new AtomicLong();
			ApiEventsConsumer consumer = consumer(new ApiEventsSettings(Delivery.ASYNC, 5_000, Overflow.BLOCK, 100, 1),
					dropped);
			start(consumer, bytes -> {
				awaitQuietly(release);
				return CompletableFuture.completedFuture(true);
			});

			fire("held by the route");
			TimeUnit.MILLISECONDS.sleep(100);
			fire("queued");
			fire("waits, then dropped");
			release.countDown();

			assertEquals(1, dropped.get());
		}
	}

	@Nested
	@DisplayName("sync")
	class Synchronous {

		private final ApiEventsSettings sync = new ApiEventsSettings(Delivery.SYNC, 300, Overflow.DROP, 1_000, 10);

		@Test
		@DisplayName("a route that records: the operation goes on, numbered as ever")
		void recorded() throws Exception {
			List<byte[]> received = Collections.synchronizedList(new ArrayList<>());
			start(consumer(this.sync, new AtomicLong()), bytes -> {
				received.add(bytes);
				return CompletableFuture.completedFuture(true);
			});

			assertDoesNotThrow(() -> fire("t"));

			assertEquals(1, received.size(), "delivered before fire returned: no queue");
			assertEquals(1, json(received.get(0)).get("seq").asLong());
		}

		@Test
		@DisplayName("a route that fails fails the operation")
		void failed() throws Exception {
			start(consumer(this.sync, new AtomicLong()), bytes -> CompletableFuture.completedFuture(false));

			ObserverFailureException failure = assertThrows(ObserverFailureException.class, () -> fire("t"));
			assertTrue(failure.getCause().getMessage().contains("failed"), failure.getMessage());
		}

		@Test
		@DisplayName("a route that does not answer in time fails the operation")
		void timeout() throws Exception {
			start(consumer(this.sync, new AtomicLong()), bytes -> new CompletableFuture<>());

			ObserverFailureException failure = assertThrows(ObserverFailureException.class, () -> fire("t"));
			assertTrue(failure.getCause().getMessage().contains("300 ms"), failure.getMessage());
		}

		@Test
		@DisplayName("started without the route's outcome, it refuses: it could not fail anything")
		void withoutOutcome() {
			ApiEventsConsumer consumer = consumer(this.sync, new AtomicLong());
			assertThrows(ConnectorException.class, () -> consumer.start(bytes -> { }));
		}
	}

	@Nested
	@DisplayName("settings")
	class Settings {

		@Test
		@DisplayName("absent keys keep the behaviour of before")
		void defaults() {
			assertEquals(ApiEventsSettings.DEFAULTS, ApiEventsSettings.of(Map.of()));
		}

		@Test
		@DisplayName("documented values are read, case-insensitively")
		void read() {
			assertEquals(new ApiEventsSettings(Delivery.SYNC, 250, Overflow.BLOCK, 2_000, 500), ApiEventsSettings.of(
					Map.of("delivery", "Sync", "deliveryTimeout", "250", "overflow", "block", "overflowTimeout", "2000",
							"queueCapacity", "500")));
		}

		@Test
		@DisplayName("anything else is refused with what was expected")
		void refused() {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
					() -> ApiEventsSettings.of(Map.of("overflow", "spill")));
			assertTrue(e.getMessage().contains("[drop, block]"), e.getMessage());
			assertThrows(IllegalArgumentException.class, () -> ApiEventsSettings.of(Map.of("queueCapacity", "0")));
			assertThrows(IllegalArgumentException.class, () -> ApiEventsSettings.of(Map.of("deliveryTimeout", "soon")));
		}
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void sleepQuietly(long millis) {
		try {
			TimeUnit.MILLISECONDS.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
