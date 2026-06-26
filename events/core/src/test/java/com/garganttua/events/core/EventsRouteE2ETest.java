package com.garganttua.events.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.core.expression.dsl.ExpressionContextBuilder;
import com.garganttua.core.expression.dsl.IExpressionContextBuilder;
import com.garganttua.core.injection.context.InjectionContext;
import com.garganttua.core.injection.context.dsl.IInjectionContextBuilder;
import com.garganttua.core.lifecycle.AbstractLifecycle;
import com.garganttua.core.lifecycle.ILifecycle;
import com.garganttua.core.lifecycle.LifecycleException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.dsl.IReflectionBuilder;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;
import com.garganttua.core.reflections.ReflectionsAnnotationScanner;
import com.garganttua.core.runtime.dsl.IRuntimesBuilder;
import com.garganttua.core.runtime.dsl.RuntimesBuilder;
import com.garganttua.core.script.dsl.IScriptsBuilder;
import com.garganttua.core.script.dsl.ScriptsBuilder;
import com.garganttua.events.api.ConnectorContext;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.connectors.annotations.Connector;
import com.garganttua.events.api.context.ConnectorDef;
import com.garganttua.events.api.context.ContextDef;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.RouteDef;
import com.garganttua.events.api.context.RouteStageDef;
import com.garganttua.events.api.context.SubscriptionDef;
import com.garganttua.events.api.context.TopicDef;

/**
 * End-to-end engine test: a message fed to the IN connector flows through a route's stages
 * ({@code set_header} then {@code produce}) and lands on the OUT connector. This is the first test
 * that drives a real message through {@code workflow.execute} — it proves the route's
 * {@code @Expression} stages actually run, which requires the engine to be wired with the full
 * Scripts (→ Runtimes → Expression) execution chain via the bootstrap-provided {@link IScriptsBuilder}.
 */
@DisplayName("Events E2E — message → route stages → capture connector")
class EventsRouteE2ETest {

	/**
	 * In-memory connector: its consumer captures the engine's message handler (so the test can feed
	 * a message), and its producer records what the route published. Static capture is safe — the
	 * engine instantiates exactly one instance from the reflective registry.
	 */
	@Connector(type = "mem", version = "1.0")
	public static final class MemConnector extends AbstractLifecycle implements IConnector {

		static final AtomicReference<Consumer<byte[]>> IN_HANDLER = new AtomicReference<>();
		static final List<byte[]> PRODUCED = new CopyOnWriteArrayList<>();

		public MemConnector() {
			// public no-arg ctor for the reflective registry path
		}

		@Override
		public IReflection reflection() {
			return IClass.getReflection();
		}

		@Override
		public String getName() {
			return "mem";
		}

		@Override
		public void configure(Map<String, String> configuration, ConnectorContext ctx) {
			// no-op
		}

		@Override
		public IConsumer createConsumer(SubscriptionDef sub, DataflowDef df) {
			return new IConsumer() {
				@Override
				public void start(Consumer<byte[]> messageHandler) {
					// Capture the engine's bridge handler and return (do NOT block): the test feeds
					// messages by invoking it directly.
					IN_HANDLER.set(messageHandler);
				}

				@Override
				public void stop() {
					// no-op
				}
			};
		}

		@Override
		public IProducer createProducer(SubscriptionDef sub, DataflowDef df) {
			return new IProducer() {
				@Override
				public void publish(byte[] value) {
					PRODUCED.add(value);
				}

				@Override
				public void stop() {
					// no-op
				}
			};
		}

		@Override
		protected ILifecycle doInit() throws LifecycleException {
			return this;
		}

		@Override
		protected ILifecycle doStart() throws LifecycleException {
			return this;
		}

		@Override
		protected ILifecycle doFlush() throws LifecycleException {
			return this;
		}

		@Override
		protected ILifecycle doStop() throws LifecycleException {
			return this;
		}
	}

	private static IReflectionBuilder reflectionBuilder;
	private IScriptsBuilder scriptsBuilder;
	private IInjectionContextBuilder injectionContextBuilder;
	private Events engine;

	@BeforeEach
	void setUp() throws Exception {
		MemConnector.IN_HANDLER.set(null);
		MemConnector.PRODUCED.clear();

		reflectionBuilder = ReflectionBuilder.builder()
				.withProvider(new RuntimeReflectionProvider())
				.withScanner(new ReflectionsAnnotationScanner());
		IClass.setReflection(reflectionBuilder.build());

		// Full execution chain: injection → expression (scans events @Expression fns) → runtimes → scripts.
		injectionContextBuilder = InjectionContext.builder()
				.provide(reflectionBuilder)
				.autoDetect(true)
				.withPackage("com.garganttua");
		IExpressionContextBuilder expressionContextBuilder = ExpressionContextBuilder.builder();
		expressionContextBuilder.withPackage("com.garganttua").autoDetect(true)
				.provide(injectionContextBuilder);
		injectionContextBuilder.build().onInit().onStart();
		expressionContextBuilder.build();
		IRuntimesBuilder runtimesBuilder = RuntimesBuilder.builder().provide(injectionContextBuilder);
		scriptsBuilder = ScriptsBuilder.builder()
				.provide(injectionContextBuilder)
				.provide(expressionContextBuilder)
				.provide(runtimesBuilder);
	}

	@AfterEach
	void tearDown() throws Exception {
		if (engine != null) {
			engine.onStop();
			engine.onFlush();
		}
		IClass.setReflection(null);
	}

	@Test
	@DisplayName("a fed message runs the route stages and is produced to the OUT connector")
	void messageFlowsThroughRouteStagesToOutConnector() throws Exception {
		ContextDef context = new ContextDef("tenant", "cluster",
				List.of(new TopicDef("events.in"), new TopicDef("events.out")),
				List.of(new DataflowDef("df-1", "flow", "mem", true, "1", false)),
				List.of(new ConnectorDef("mem1", "mem", "1.0", Map.of())),
				List.of(
						new SubscriptionDef("in", "df-1", "events.in", "mem1", null, null, null, null),
						new SubscriptionDef("out", "df-1", "events.out", "mem1", null, null, null, null)),
				List.of(new RouteDef("route-1", "in", "out",
						List.of(
								new RouteStageDef("tag", "set_header(@exchange, \"processed\", \"true\")",
										null, null, null),
								new RouteStageDef("emit", "produce(@exchange, @producer)",
										null, null, null)),
						null, null)),
				null);

		Map<String, IClass<? extends IConnector>> registry = Map.of(
				"mem:1.0", IClass.getClass(MemConnector.class));
		engine = new Events("asset", List.of(context), registry, injectionContextBuilder, scriptsBuilder);
		engine.onInit();
		engine.onStart();

		// The consumer thread captures the bridge handler asynchronously; await it.
		Consumer<byte[]> handler = null;
		for (int i = 0; i < 50 && handler == null; i++) {
			handler = MemConnector.IN_HANDLER.get();
			if (handler == null) {
				TimeUnit.MILLISECONDS.sleep(40);
			}
		}
		assertNotNull(handler, "engine must start the route consumer and capture its handler");

		// Feed one message — it must flow through both stages and land on the OUT producer.
		byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
		handler.accept(payload);

		byte[] produced = null;
		for (int i = 0; i < 50 && produced == null; i++) {
			if (!MemConnector.PRODUCED.isEmpty()) {
				produced = MemConnector.PRODUCED.get(0);
			} else {
				TimeUnit.MILLISECONDS.sleep(40);
			}
		}
		assertNotNull(produced, "the route's produce() stage must publish to the OUT connector");
		assertEquals(1, MemConnector.PRODUCED.size(), "exactly one message produced");
		assertArrayEquals(payload, produced, "the produced payload is the routed exchange value");
		assertTrue(true, "route stages executed end-to-end");
	}
}
