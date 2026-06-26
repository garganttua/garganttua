package com.garganttua.events.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.core.observability.EndEvent;
import com.garganttua.core.observability.ErrorEvent;
import com.garganttua.core.observability.IObserver;
import com.garganttua.core.observability.ObservableEvent;
import com.garganttua.core.observability.ObservableRegistry;
import com.garganttua.core.observability.StartEvent;
import com.garganttua.core.workflow.IWorkflow;
import com.garganttua.core.workflow.WorkflowExecutionOptions;
import com.garganttua.core.workflow.WorkflowInput;
import com.garganttua.core.workflow.WorkflowResult;
import com.garganttua.core.workflow.dsl.WorkflowDescriptor;
import com.garganttua.events.api.Exchange;

/**
 * Verifies {@link RouteObserver}: it emits {@code events:route:<uuid>} Start/End events carrying the
 * {@link Exchange}, surfaces an {@link ErrorEvent} (with the Exchange) when the workflow throws, and
 * runs the workflow directly with no emission on the fast path (no observer registered).
 */
@DisplayName("RouteObserver")
class RouteObserverTest {

	private static final String ROUTE_UUID = "route-1234";

	/** A stub workflow that records whether it ran and optionally throws on execution. */
	private static final class StubWorkflow implements IWorkflow {
		private final AtomicBoolean ran = new AtomicBoolean(false);
		private final RuntimeException toThrow;

		StubWorkflow(RuntimeException toThrow) {
			this.toThrow = toThrow;
		}

		boolean ran() {
			return ran.get();
		}

		@Override
		public WorkflowResult execute(WorkflowInput input) {
			ran.set(true);
			if (toThrow != null) {
				throw toThrow;
			}
			return null;
		}

		@Override
		public WorkflowResult execute(WorkflowInput input, WorkflowExecutionOptions options) {
			return execute(input);
		}

		@Override
		public WorkflowResult execute() {
			return execute(null);
		}

		@Override
		public String getName() {
			return "stub";
		}

		@Override
		public String getGeneratedScript() {
			return "";
		}

		@Override
		public String describeWorkflow() {
			return "";
		}

		@Override
		public WorkflowDescriptor getDescriptor() {
			return null;
		}
	}

	private static Exchange exchange() {
		return Exchange.create("connector", "topic", "dataflow", new byte[] {1, 2, 3});
	}

	private static WorkflowInput inputFor(Exchange exchange) {
		return WorkflowInput.of(exchange);
	}

	@Nested
	@DisplayName("with an observer attached")
	class Observed {

		@Test
		@DisplayName("a successful route emits Start then End carrying the Exchange")
		void successEmitsStartAndEnd() {
			ObservableRegistry registry = new ObservableRegistry();
			List<ObservableEvent> seen = new ArrayList<>();
			IObserver<ObservableEvent> probe = seen::add;
			registry.addObserver(probe);

			RouteObserver routeObserver = new RouteObserver(registry);
			Exchange exchange = exchange();
			StubWorkflow workflow = new StubWorkflow(null);

			routeObserver.execute(ROUTE_UUID, workflow, inputFor(exchange), exchange);

			assertTrue(workflow.ran(), "the workflow must run");
			assertEquals(2, seen.size(), "exactly a Start and an End event must be emitted");

			ObservableEvent start = seen.get(0);
			assertTrue(start instanceof StartEvent, "first event must be a StartEvent");
			assertEquals("events:route:" + ROUTE_UUID, start.source(), "source must be events:route:<uuid>");

			ObservableEvent end = seen.get(1);
			assertTrue(end instanceof EndEvent, "second event must be an EndEvent");
			assertEquals("events:route:" + ROUTE_UUID, end.source(), "End source must match");
			assertSame(exchange, ((EndEvent) end).payload(), "End payload must be the Exchange");
			assertEquals(start.executionId(), end.executionId(), "Start/End must share the execution id");
		}

		@Test
		@DisplayName("a throwing route emits an ErrorEvent carrying the Exchange and rethrows")
		void failureEmitsErrorAndRethrows() {
			ObservableRegistry registry = new ObservableRegistry();
			List<ObservableEvent> seen = new ArrayList<>();
			registry.addObserver(seen::add);

			RouteObserver routeObserver = new RouteObserver(registry);
			Exchange exchange = exchange();
			IllegalStateException boom = new IllegalStateException("boom");
			StubWorkflow workflow = new StubWorkflow(boom);

			RuntimeException thrown = assertThrows(IllegalStateException.class,
					() -> routeObserver.execute(ROUTE_UUID, workflow, inputFor(exchange), exchange),
					"the original RuntimeException must propagate");
			assertSame(boom, thrown, "the same exception instance must be rethrown unchanged");

			assertEquals(2, seen.size(), "a Start and an Error event must be emitted");
			assertTrue(seen.get(0) instanceof StartEvent, "first event must be a StartEvent");

			ObservableEvent error = seen.get(1);
			assertTrue(error instanceof ErrorEvent, "second event must be an ErrorEvent");
			assertEquals("events:route:" + ROUTE_UUID, error.source(), "Error source must match");
			assertSame(exchange, ((ErrorEvent) error).payload(), "Error payload must be the Exchange");
			assertSame(boom, ((ErrorEvent) error).failure(), "Error must carry the original failure");
		}
	}

	@Nested
	@DisplayName("fast path (no observer)")
	class FastPath {

		@Test
		@DisplayName("runs the workflow directly and emits nothing")
		void runsWorkflowWithoutEmitting() {
			ObservableRegistry registry = new ObservableRegistry();
			List<ObservableEvent> seen = new ArrayList<>();
			IObserver<ObservableEvent> probe = seen::add;
			// probe is deliberately NOT attached to the registry: with no local and no global
			// observer, the fast-path guard must short-circuit and emit nothing.

			RouteObserver routeObserver = new RouteObserver(registry);
			Exchange exchange = exchange();
			StubWorkflow workflow = new StubWorkflow(null);

			assertFalse(registry.hasObservers(), "precondition: registry has no observer");
			routeObserver.execute(ROUTE_UUID, workflow, inputFor(exchange), exchange);

			assertTrue(workflow.ran(), "the workflow must still run on the fast path");
			assertTrue(seen.isEmpty(), "no event must be emitted when nothing observes");

			// Sanity: the probe is a working sink — attaching it and firing once would record an
			// event. This proves the empty assertion above is meaningful, not a dead observer.
			registry.addObserver(probe);
			registry.fire(new StartEvent(null, java.time.Instant.now(), "events:route:probe"));
			registry.removeObserver(probe);
			assertEquals(1, seen.size(), "the probe records when actually attached and fired");
		}
	}
}
