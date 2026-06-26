package com.garganttua.core.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.garganttua.core.observability.ObservabilityEmitter.Scope;

class ObservabilityEmitterBehaviourTest {

	@AfterEach
	void clearHolder() {
		ObservableContextHolder.pop(null);
	}

	private ObservableRegistry observed(List<ObservableEvent> sink) {
		ObservableRegistry reg = new ObservableRegistry();
		reg.addObserver(sink::add);
		return reg;
	}

	@Test
	void open_noParent_pushesNewSessionAndPopsOnClose() {
		List<ObservableEvent> sink = new ArrayList<>();
		ObservableRegistry reg = observed(sink);
		UUID id = UUID.randomUUID();

		try (Scope scope = ObservabilityEmitter.open(reg, id)) {
			assertEquals(id, scope.executionId());
			assertNotNull(ObservableContextHolder.current(),
					"open with no parent must push a session");
			assertEquals(id, ObservableContextHolder.current().executionId());
		}
		assertNull(ObservableContextHolder.current(),
				"closing the owning scope must clear the holder");
	}

	@Test
	void open_withParent_reusesParentRegistryAndExecutionId() {
		List<ObservableEvent> parentSink = new ArrayList<>();
		ObservableRegistry parentReg = observed(parentSink);
		UUID parentId = UUID.randomUUID();

		ObservableContextHolder.Session prev = ObservableContextHolder.push(parentReg, parentId);
		try {
			ObservableRegistry localReg = new ObservableRegistry();
			try (Scope scope = ObservabilityEmitter.open(localReg, UUID.randomUUID())) {
				assertEquals(parentId, scope.executionId(),
						"nested scope inherits the parent's executionId");
				scope.fireStart("child");
			}
			// after closing the nested scope the parent session must remain bound
			assertSame(parentReg, ObservableContextHolder.current().registry());
			assertEquals(1, parentSink.size(),
					"the child's StartEvent reaches the parent registry");
			assertEquals(parentId, parentSink.get(0).executionId());
		} finally {
			ObservableContextHolder.pop(prev);
		}
	}

	@Test
	void fireStart_emitsStartEventWithSourceAndPayload() {
		List<ObservableEvent> sink = new ArrayList<>();
		try (Scope scope = ObservabilityEmitter.open(observed(sink), UUID.randomUUID())) {
			Object payload = "the-input";
			scope.fireStart("unit:work", payload);

			assertEquals(1, sink.size());
			StartEvent ev = (StartEvent) sink.get(0);
			assertEquals("unit:work", ev.source());
			assertSame(payload, ev.payload());
		}
	}

	@Test
	void fireEnd_emitsEndEventWithCodeAndNonNegativeDuration() {
		List<ObservableEvent> sink = new ArrayList<>();
		try (Scope scope = ObservabilityEmitter.open(observed(sink), UUID.randomUUID())) {
			scope.fireEnd("unit:work", 200);

			EndEvent ev = (EndEvent) sink.get(0);
			assertEquals("unit:work", ev.source());
			assertEquals(200, ev.code());
			assertTrue(ev.duration().compareTo(Duration.ZERO) >= 0,
					"duration must be non-negative");
		}
	}

	@Test
	void fireError_emitsErrorEventCarryingFailure() {
		List<ObservableEvent> sink = new ArrayList<>();
		try (Scope scope = ObservabilityEmitter.open(observed(sink), UUID.randomUUID())) {
			IllegalStateException failure = new IllegalStateException("kaboom");
			scope.fireError("unit:work", failure);

			ErrorEvent ev = (ErrorEvent) sink.get(0);
			assertEquals("unit:work", ev.source());
			assertSame(failure, ev.failure());
		}
	}

	@Test
	void fireLog_emitsLogEventWithLevelAndMessage() {
		List<ObservableEvent> sink = new ArrayList<>();
		try (Scope scope = ObservabilityEmitter.open(observed(sink), UUID.randomUUID())) {
			scope.fireLog("unit:work", LogEvent.Level.WARN, "careful");

			LogEvent ev = (LogEvent) sink.get(0);
			assertEquals(LogEvent.Level.WARN, ev.level());
			assertEquals("careful", ev.message());
			assertEquals("unit:work", ev.source());
		}
	}

	@Test
	void noObserver_fireMethodsAreNoop() {
		ObservableRegistry reg = new ObservableRegistry(); // no observer
		try (Scope scope = ObservabilityEmitter.open(reg, UUID.randomUUID())) {
			assertFalse(scope.hasObservers());
			// none of these must throw
			scope.fireStart("x");
			scope.fireEnd("x");
			scope.fireError("x", new RuntimeException());
			scope.fireLog("x", LogEvent.Level.INFO, "m");
		}
	}

	@Test
	void joinCurrent_withNoParent_isNoopScope() {
		try (Scope scope = ObservabilityEmitter.joinCurrent()) {
			assertNull(scope.executionId());
			assertFalse(scope.hasObservers());
			scope.fireStart("orphan"); // must not throw
		}
		assertNull(ObservableContextHolder.current(),
				"joinCurrent with no parent must not push anything");
	}

	@Test
	void joinCurrent_withParent_piggyBacksOnParentSession() {
		List<ObservableEvent> parentSink = new ArrayList<>();
		ObservableRegistry parentReg = observed(parentSink);
		UUID parentId = UUID.randomUUID();

		ObservableContextHolder.Session prev = ObservableContextHolder.push(parentReg, parentId);
		try {
			try (Scope scope = ObservabilityEmitter.joinCurrent()) {
				assertEquals(parentId, scope.executionId());
				assertTrue(scope.hasObservers());
				scope.fireStart("step");
			}
			assertEquals(1, parentSink.size());
			assertEquals(parentId, parentSink.get(0).executionId());
			// closing a join scope must NOT pop the parent
			assertSame(parentReg, ObservableContextHolder.current().registry());
		} finally {
			ObservableContextHolder.pop(prev);
		}
	}

	@Test
	void startedAt_isCapturedAtOpenTime() {
		ObservableRegistry reg = new ObservableRegistry();
		try (Scope scope = ObservabilityEmitter.open(reg, UUID.randomUUID())) {
			assertNotNull(scope.startedAt());
		}
	}

	@Test
	void fire_reachesGlobalFirehoseEvenWhenLocalRegistryHasNoObserver() {
		// Regression: a cross-cutting sink (e.g. an event connector) registers on the process-global
		// GlobalObservers firehose, NOT on any per-engine registry. The scope's hasObservers() must
		// therefore consider the firehose, else fire*() short-circuits and the sink is starved —
		// which silently dropped every api:operation:* event for the events api-connector.
		List<ObservableEvent> firehose = new ArrayList<>();
		IObserver<ObservableEvent> globalObs = firehose::add;
		GlobalObservers.addObserver(globalObs);
		try {
			ObservableRegistry bareLocal = new ObservableRegistry(); // no local observer
			try (Scope scope = ObservabilityEmitter.open(bareLocal, UUID.randomUUID())) {
				assertTrue(scope.hasObservers(),
						"a registered global observer must keep the scope active despite an empty local registry");
				scope.fireEnd("api:operation:contacts:create", 200);
			}
			assertEquals(1, firehose.size(),
					"the firehose observer must receive the event though no local observer is attached");
			assertEquals("api:operation:contacts:create", firehose.get(0).source());
		} finally {
			GlobalObservers.removeObserver(globalObs);
		}
	}

	@Test
	void open_firesToBothActiveAndLocalWhenNested() {
		// parent active registry + distinct local registry: nested open reuses
		// parent as "active" but keeps its own "local" registry, so both observe.
		List<ObservableEvent> parentSink = new ArrayList<>();
		List<ObservableEvent> localSink = new ArrayList<>();
		ObservableRegistry parentReg = observed(parentSink);
		ObservableRegistry localReg = observed(localSink);

		ObservableContextHolder.Session prev =
				ObservableContextHolder.push(parentReg, UUID.randomUUID());
		try {
			try (Scope scope = ObservabilityEmitter.open(localReg, UUID.randomUUID())) {
				scope.fireStart("dual");
			}
			assertEquals(1, parentSink.size(), "active (parent) registry receives it");
			assertEquals(1, localSink.size(), "local registry also receives it");
		} finally {
			ObservableContextHolder.pop(prev);
		}
	}
}
