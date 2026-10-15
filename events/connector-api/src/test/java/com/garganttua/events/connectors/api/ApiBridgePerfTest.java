package com.garganttua.events.connectors.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.service.OperationResponseCode;
import com.garganttua.core.observability.EndEvent;
import com.garganttua.core.observability.GlobalObservers;
import com.garganttua.core.observability.IObserver;
import com.garganttua.core.observability.ObservableEvent;
import com.garganttua.core.reflection.IClass;
import com.garganttua.events.api.exceptions.ConnectorException;

/**
 * The bridge from the api to the events engine: what forwarding an api operation costs the request
 * that performed it, and how much of a burst reaches the route.
 *
 * <p>
 * The api connector listens to the global firehose. Its listener runs ON THE REQUEST THREAD: it
 * filters the event, encodes the business event to JSON, and queues the bytes for its own thread,
 * which feeds the route. Everything before the queue is added to the latency of every api request;
 * everything after it is not. api-core's {@code EventEmissionPerfTest} measures what the api pays to
 * build the events for any listener; this test measures what this listener adds on top, and the
 * route side is replaced by a handler that only counts — the mocked bus.
 * </p>
 *
 * <pre>{@code
 * mvn -o test -pl :garganttua-events-connector-api -Dtest='ApiBridgePerfTest' -Dgarganttua.perf=true -Djacoco.skip=true
 * }</pre>
 */
@EnabledIfSystemProperty(named = "garganttua.perf", matches = "true",
		disabledReason = "performance measurement — opt in with -Dgarganttua.perf=true -Djacoco.skip=true")
@DisplayName("api → events bridge cost (mocked bus)")
class ApiBridgePerfTest {

	private static final int WARMUP = Integer.getInteger("garganttua.perf.warmup", 5_000);
	private static final int RUNS = Integer.getInteger("garganttua.perf.runs", 20_000);
	/** The consumer's queue holds 10,000 events: a burst of that size must arrive whole. */
	private static final int QUEUE_CAPACITY = 10_000;

	static final class Order {
	}

	private final List<PerfHarness.Stats> collected = new ArrayList<>();
	private final List<String> notes = new ArrayList<>();

	@BeforeAll
	static void installReflection() {
		TestReflection.install();
	}

	/** An api operation as the firehose carries it: an end event whose payload is the business event. */
	private static EndEvent operationEnded(int i) {
		FakeEvent business = new FakeEvent();
		business.setOperation(OperationDefinition.createOneWithStandardSecurity("orders", IClass.getClass(Order.class)));
		business.setTenantId("tenant-" + (i % 10));
		business.setUserId("user-" + i);
		business.setCode(OperationResponseCode.CREATED);
		business.setOut("order-" + i);
		return new EndEvent(UUID.randomUUID(), Instant.now(), "api:operation:orders:createOne",
				Duration.ofMillis(1), 0, business);
	}

	/** A started consumer and what its handler — the route, mocked — has received. */
	private record Bridge(ApiEventsConsumer consumer, Thread thread, AtomicLong delivered) {

		static Bridge start(String operation, String domain) throws InterruptedException {
			ApiEventsConsumer consumer = new ApiEventsConsumer(operation, domain);
			AtomicLong delivered = new AtomicLong();
			Thread thread = new Thread(() -> {
				try {
					consumer.start(bytes -> delivered.incrementAndGet());
				} catch (ConnectorException e) {
					throw new IllegalStateException(e);
				}
			}, "api-bridge-perf");
			thread.setDaemon(true);
			thread.start();
			// start() registers the listener before it blocks on the queue; wait until it has.
			for (int i = 0; i < 250 && !GlobalObservers.hasObservers(); i++) {
				TimeUnit.MILLISECONDS.sleep(10);
			}
			assertTrue(GlobalObservers.hasObservers(), "the consumer never attached to the firehose");
			return new Bridge(consumer, thread, delivered);
		}

		boolean await(long count, long timeoutMillis) throws InterruptedException {
			long deadline = System.currentTimeMillis() + timeoutMillis;
			while (this.delivered.get() < count && System.currentTimeMillis() < deadline) {
				TimeUnit.MILLISECONDS.sleep(5);
			}
			return this.delivered.get() >= count;
		}

		void stop() throws Exception {
			this.consumer.stop();
			this.thread.join(5_000);
		}
	}

	// ─────────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("what the connector's listener adds to the request thread, per api operation")
	void costOnTheRequestThread() throws Exception {
		EndEvent[] events = new EndEvent[1_000];
		for (int i = 0; i < events.length; i++) {
			events[i] = operationEnded(i);
		}
		IObserver<ObservableEvent> nothing = event -> {
			// the cheapest listener: what firing costs before any connector does anything
		};
		GlobalObservers.addObserver(nothing);
		PerfHarness.Stats bare;
		try {
			bare = record(PerfHarness.measure("fire — no-op listener", WARMUP, RUNS,
					i -> GlobalObservers.fire(events[i % events.length])));
		} finally {
			GlobalObservers.removeObserver(nothing);
		}

		Bridge bridge = Bridge.start(null, null);
		PerfHarness.Stats forwarded;
		try {
			forwarded = record(PerfHarness.measure("fire — api connector listening", WARMUP, RUNS, i -> {
				GlobalObservers.fire(events[i % events.length]);
				// Keep the queue from filling: a full queue drops instead of encoding, which would
				// time the cheap path. The drain thread keeps up in practice; this makes sure of it.
				if (i % 5_000 == 4_999) {
					awaitQuietly(bridge, i + 1L);
				}
			}));
			assertTrue(bridge.await(WARMUP + RUNS, 30_000),
					"every event reached the route: " + bridge.delivered().get() + " of " + (WARMUP + RUNS));
		} finally {
			bridge.stop();
		}

		this.notes.add(String.format(Locale.ROOT,
				"the api connector adds %.1f us to every api operation's request thread (floor %.1f -> %.1f us)",
				forwarded.minUs() - bare.minUs(), bare.minUs(), forwarded.minUs()));
	}

	@Test
	@DisplayName("an event the connector filters out costs the request (almost) nothing")
	void filteredOutIsCheap() throws Exception {
		EndEvent event = operationEnded(0);
		Bridge other = Bridge.start(null, "invoices");
		PerfHarness.Stats filtered;
		try {
			filtered = record(PerfHarness.measure("fire — filtered out by domain", WARMUP, RUNS,
					i -> GlobalObservers.fire(event)));
		} finally {
			other.stop();
		}
		Bridge same = Bridge.start(null, "orders");
		PerfHarness.Stats kept;
		try {
			kept = record(PerfHarness.measure("fire — kept by domain", WARMUP, RUNS, i -> {
				GlobalObservers.fire(event);
				if (i % 5_000 == 4_999) {
					awaitQuietly(same, i + 1L);
				}
			}));
		} finally {
			same.stop();
		}
		assertEquals(0, other.delivered().get(), "a domain filter forwards nothing of another domain");
		// The filter decides on the event's source string, before encoding: rejecting must cost a
		// fraction of forwarding, or the filter runs after the expensive part.
		assertTrue(filtered.minUs() < kept.minUs(), () -> "rejecting an event costs " + filtered.minUs()
				+ " us, forwarding it " + kept.minUs() + " us — the filter runs after the encoding." + report());
	}

	@Test
	@DisplayName("a burst the size of the queue reaches the route whole")
	void burstWithinCapacityIsDelivered() throws Exception {
		Bridge bridge = Bridge.start(null, null);
		try {
			long start = System.nanoTime();
			for (int i = 0; i < QUEUE_CAPACITY; i++) {
				GlobalObservers.fire(operationEnded(i));
			}
			double fireSeconds = (System.nanoTime() - start) / 1e9d;
			assertTrue(bridge.await(QUEUE_CAPACITY, 30_000),
					"delivered " + bridge.delivered().get() + " of a " + QUEUE_CAPACITY + "-event burst");
			double totalSeconds = (System.nanoTime() - start) / 1e9d;
			this.notes.add(String.format(Locale.ROOT,
					"burst of %d: fired at %.0f ev/s, all delivered to the route at %.0f ev/s",
					QUEUE_CAPACITY, QUEUE_CAPACITY / fireSeconds, QUEUE_CAPACITY / totalSeconds));
		} finally {
			bridge.stop();
		}
	}

	// ─────────────────────────────────────────────────────────────────────────

	private static void awaitQuietly(Bridge bridge, long count) {
		try {
			bridge.await(count, 10_000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private PerfHarness.Stats record(PerfHarness.Stats stats) {
		this.collected.add(stats);
		return stats;
	}

	private String report() {
		StringBuilder sb = new StringBuilder(PerfHarness.report("api → events bridge, mocked bus:", this.collected));
		this.notes.forEach(n -> sb.append(n).append(System.lineSeparator()));
		return sb.toString();
	}

	@AfterEach
	void publish() {
		if (!this.collected.isEmpty() || !this.notes.isEmpty()) {
			System.out.println(report());
		}
	}
}
