package com.garganttua.core.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link GlobalObservers} firehose: events reach added observers, removed observers
 * stop receiving, exceptions are isolated per observer, and null arguments are ignored — except a
 * BLOCKING observer's, raised once every observer had the event.
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

	@Test
	void blockingObserverFailureIsRaisedOnceEveryObserverHadTheEvent() {
		AtomicInteger ordinary = new AtomicInteger();
		AtomicInteger secondBlocking = new AtomicInteger();
		IObserver<ObservableEvent> good = e -> ordinary.incrementAndGet();
		IObserver<ObservableEvent> failing = e -> {
			throw new IllegalStateException("broker down");
		};
		IObserver<ObservableEvent> other = e -> secondBlocking.incrementAndGet();
		GlobalObservers.addObserver(good);
		GlobalObservers.addBlockingObserver(failing);
		GlobalObservers.addBlockingObserver(other);
		try {
			StartEvent e = event();
			ObserverFailureException raised = assertThrows(ObserverFailureException.class, () -> GlobalObservers.fire(e));
			assertTrue(raised.getCause() instanceof IllegalStateException, "the observer's own failure is the cause");
			assertTrue(raised.getMessage().contains("test:source"), raised.getMessage());
			assertEquals(1, ordinary.get(), "the ordinary observer had the event");
			assertEquals(1, secondBlocking.get(), "so did the other blocking observer");
		} finally {
			GlobalObservers.removeObserver(good);
			GlobalObservers.removeObserver(failing);
			GlobalObservers.removeObserver(other);
		}
	}

	@Test
	void severalBlockingFailuresAreKeptTogether() {
		IObserver<ObservableEvent> first = e -> {
			throw new IllegalStateException("first");
		};
		IObserver<ObservableEvent> second = e -> {
			throw new IllegalArgumentException("second");
		};
		GlobalObservers.addBlockingObserver(first);
		GlobalObservers.addBlockingObserver(second);
		try {
			ObserverFailureException raised = assertThrows(ObserverFailureException.class,
					() -> GlobalObservers.fire(event()));
			assertEquals(1, raised.getSuppressed().length, "the second failure travels with the first");
		} finally {
			GlobalObservers.removeObserver(first);
			GlobalObservers.removeObserver(second);
		}
	}

	@Test
	void aBlockingObserverAloneMakesTheFirehoseActive() {
		IObserver<ObservableEvent> blocking = e -> { };
		GlobalObservers.addBlockingObserver(blocking);
		try {
			assertTrue(GlobalObservers.hasObservers(), "the fast paths that skip emission must not skip it");
		} finally {
			GlobalObservers.removeObserver(blocking);
		}
		assertDoesNotThrow(() -> GlobalObservers.addBlockingObserver(null));
	}

	@Test
	void theEmitterRaisesABlockingFailureAfterItsOwnRegistries() {
		AtomicInteger local = new AtomicInteger();
		ObservableRegistry registry = new ObservableRegistry();
		registry.addObserver(e -> local.incrementAndGet());
		IObserver<ObservableEvent> failing = e -> {
			throw new IllegalStateException("broker down");
		};
		GlobalObservers.addBlockingObserver(failing);
		try (var scope = ObservabilityEmitter.open(registry, UUID.randomUUID())) {
			assertThrows(ObserverFailureException.class, () -> scope.fireStart("test:source"));
			assertTrue(local.get() >= 1, "the engine's own observers had the event before the failure was raised");
		} finally {
			GlobalObservers.removeObserver(failing);
		}
	}
}
