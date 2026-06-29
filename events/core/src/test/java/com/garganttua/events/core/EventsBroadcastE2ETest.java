package com.garganttua.events.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * End-to-end engine test for multi-{@code .to()} fan-out: one inbound message routed by a single
 * route with two output subscriptions ({@code to: [subA, subB]}) is broadcast to <em>both</em>
 * destinations. A single-{@code .to()} route still delivers to exactly one (backward compatibility).
 */
@DisplayName("Events E2E — one message broadcast to multiple destination subscriptions")
class EventsBroadcastE2ETest {

	/**
	 * In-memory connector capturing the engine's inbound handler and recording every produced payload
	 * keyed by the destination subscription topic — so a fan-out to two subscriptions (different
	 * topics) lands in two distinct buckets. One instance is created from the reflective registry.
	 */
	@Connector(type = "bmem", version = "1.0")
	public static final class BroadcastConnector extends AbstractLifecycle implements IConnector {

		static final AtomicReference<Consumer<byte[]>> IN_HANDLER = new AtomicReference<>();
		static final Map<String, List<byte[]>> PRODUCED_BY_TOPIC = new ConcurrentHashMap<>();

		public BroadcastConnector() {
			// public no-arg ctor for the reflective registry path
		}

		static void reset() {
			IN_HANDLER.set(null);
			PRODUCED_BY_TOPIC.clear();
		}

		@Override
		public IReflection reflection() {
			return IClass.getReflection();
		}

		@Override
		public String getName() {
			return "bmem";
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
			String topic = sub.topic();
			List<byte[]> bucket = PRODUCED_BY_TOPIC.computeIfAbsent(topic,
					k -> new CopyOnWriteArrayList<>());
			return new IProducer() {
				@Override
				public void publish(byte[] value) {
					bucket.add(value);
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
		BroadcastConnector.reset();

		reflectionBuilder = ReflectionBuilder.builder()
				.withProvider(new RuntimeReflectionProvider())
				.withScanner(new ReflectionsAnnotationScanner());
		IClass.setReflection(reflectionBuilder.build());

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
	@DisplayName("a route with to: [outA, outB] delivers one fed message to BOTH subscriptions")
	void broadcastsToEveryDestination() throws Exception {
		ContextDef context = new ContextDef("tenant", "cluster",
				List.of(new TopicDef("events.in"), new TopicDef("events.outA"),
						new TopicDef("events.outB")),
				List.of(new DataflowDef("df-1", "flow", "bmem", true, "1", false)),
				List.of(new ConnectorDef("mem1", "bmem", "1.0", Map.of())),
				List.of(
						new SubscriptionDef("in", "df-1", "events.in", "mem1", null, null, null, null),
						new SubscriptionDef("outA", "df-1", "events.outA", "mem1", null, null, null, null),
						new SubscriptionDef("outB", "df-1", "events.outB", "mem1", null, null, null, null)),
				// Fan-out: ONE route, TWO output subscriptions.
				List.of(new RouteDef("route-1", "in", List.of("outA", "outB"),
						List.of(new RouteStageDef("tag", "set_header(@exchange, \"k\", \"v\")",
								null, null, null)),
						null, null)),
				null);

		startEngine(context);
		Consumer<byte[]> handler = awaitHandler();

		byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
		handler.accept(payload);

		awaitProduced("events.outA");
		awaitProduced("events.outB");

		List<byte[]> a = BroadcastConnector.PRODUCED_BY_TOPIC.get("events.outA");
		List<byte[]> b = BroadcastConnector.PRODUCED_BY_TOPIC.get("events.outB");
		assertNotNull(a, "destination outA must receive the broadcast");
		assertNotNull(b, "destination outB must receive the broadcast");
		assertEquals(1, a.size(), "outA receives exactly one message");
		assertEquals(1, b.size(), "outB receives exactly one message");
		assertArrayEquals(payload, a.get(0), "outA gets the routed payload");
		assertArrayEquals(payload, b.get(0), "outB gets the same routed payload");
	}

	@Test
	@DisplayName("a single-.to() route still delivers to exactly one destination (backward compat)")
	void singleToStillDeliversToOne() throws Exception {
		ContextDef context = new ContextDef("tenant", "cluster",
				List.of(new TopicDef("events.in"), new TopicDef("events.outA"),
						new TopicDef("events.outB")),
				List.of(new DataflowDef("df-1", "flow", "bmem", true, "1", false)),
				List.of(new ConnectorDef("mem1", "bmem", "1.0", Map.of())),
				List.of(
						new SubscriptionDef("in", "df-1", "events.in", "mem1", null, null, null, null),
						new SubscriptionDef("outA", "df-1", "events.outA", "mem1", null, null, null, null),
						new SubscriptionDef("outB", "df-1", "events.outB", "mem1", null, null, null, null)),
				List.of(new RouteDef("route-1", "in", List.of("outA"),
						List.of(new RouteStageDef("tag", "set_header(@exchange, \"k\", \"v\")",
								null, null, null)),
						null, null)),
				null);

		startEngine(context);
		Consumer<byte[]> handler = awaitHandler();
		handler.accept("hello".getBytes(StandardCharsets.UTF_8));

		awaitProduced("events.outA");
		List<byte[]> a = BroadcastConnector.PRODUCED_BY_TOPIC.get("events.outA");
		assertNotNull(a, "the single destination receives the message");
		assertEquals(1, a.size(), "exactly one delivery");
		assertTrue(BroadcastConnector.PRODUCED_BY_TOPIC.get("events.outB") == null
				|| BroadcastConnector.PRODUCED_BY_TOPIC.get("events.outB").isEmpty(),
				"the other subscription receives nothing");
	}

	private void startEngine(ContextDef context) throws Exception {
		Map<String, IClass<? extends IConnector>> registry = Map.of(
				"bmem:1.0", IClass.getClass(BroadcastConnector.class));
		engine = new Events("asset", List.of(context), registry, injectionContextBuilder, scriptsBuilder);
		engine.onInit();
		engine.onStart();
	}

	private Consumer<byte[]> awaitHandler() throws InterruptedException {
		Consumer<byte[]> handler = null;
		for (int i = 0; i < 50 && handler == null; i++) {
			handler = BroadcastConnector.IN_HANDLER.get();
			if (handler == null) {
				TimeUnit.MILLISECONDS.sleep(40);
			}
		}
		assertNotNull(handler, "engine must start the route consumer and capture its handler");
		return handler;
	}

	private void awaitProduced(String topic) throws InterruptedException {
		for (int i = 0; i < 50; i++) {
			List<byte[]> bucket = BroadcastConnector.PRODUCED_BY_TOPIC.get(topic);
			if (bucket != null && !bucket.isEmpty()) {
				return;
			}
			TimeUnit.MILLISECONDS.sleep(40);
		}
	}
}
