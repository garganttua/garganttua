package com.garganttua.core.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link GlobalObservers} firehose: events reach added observers, removed observers
 * stop receiving, exceptions are isolated per observer, and null arguments are ignored.
 */
class GlobalObserversTest {

	private static StartEvent event() {
		return new StartEvent(UUID.randomUUID(), Instant.now(), "test:source");
	}

	@Test
	void firedEventReachesAddedObserver() {
		AtomicReference<ObservableEvent> seen = new AtomicReference<>();
		IObserver<ObservableEvent> observer = seen::set;
		GlobalObservers.addObserver(observer);
		try {
			StartEvent e = event();
			GlobalObservers.fire(e);
			assertSame(e, seen.get(), "added observer should receive the event");
		} finally {
			GlobalObservers.removeObserver(observer);
		}
	}

	@Test
	void removedObserverStopsReceiving() {
		AtomicInteger count = new AtomicInteger();
		IObserver<ObservableEvent> observer = e -> count.incrementAndGet();
		GlobalObservers.addObserver(observer);
		GlobalObservers.fire(event());
		GlobalObservers.removeObserver(observer);
		GlobalObservers.fire(event());
		assertEquals(1, count.get(), "removed observer should not receive further events");
	}

	@Test
	void throwingObserverDoesNotBreakDeliveryToOthers() {
		AtomicInteger reached = new AtomicInteger();
		IObserver<ObservableEvent> bad = e -> {
			throw new IllegalStateException("boom");
		};
		IObserver<ObservableEvent> good = e -> reached.incrementAndGet();
		GlobalObservers.addObserver(bad);
		GlobalObservers.addObserver(good);
		try {
			assertDoesNotThrow(() -> GlobalObservers.fire(event()));
			assertEquals(1, reached.get(), "good observer should still receive the event");
		} finally {
			GlobalObservers.removeObserver(bad);
			GlobalObservers.removeObserver(good);
		}
	}

	@Test
	void nullArgumentsAreIgnored() {
		assertDoesNotThrow(() -> GlobalObservers.addObserver(null));
		assertDoesNotThrow(() -> GlobalObservers.removeObserver(null));
		assertDoesNotThrow(() -> GlobalObservers.fire(null));
	}
}
