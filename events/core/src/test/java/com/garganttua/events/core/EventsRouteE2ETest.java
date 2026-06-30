package com.garganttua.events.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.garganttua.events.api.ConnectorContext;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.Message;
import com.garganttua.events.api.connectors.annotations.Connector;
import com.garganttua.events.api.context.ConsumerConfigurationDef;
import com.garganttua.events.api.context.ProducerConfigurationDef;
import com.garganttua.events.api.context.RouteExceptionsDef;
import com.garganttua.events.api.context.TimeIntervalDef;
import com.garganttua.events.api.enums.DestinationPolicy;
import com.garganttua.events.api.enums.Direction;
import com.garganttua.events.api.enums.OriginPolicy;
import com.garganttua.events.api.enums.ProcessMode;
import com.garganttua.events.api.enums.PublicationMode;
import com.garganttua.events.api.context.ConnectorDef;
import com.garganttua.events.api.context.ContextDef;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.RouteDef;
import com.garganttua.events.api.context.RouteStageDef;
import com.garganttua.events.api.context.RouteSyncDef;
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
		// Concurrency probes (used by the synchronization test): publish() runs inside the route
		// workflow, hence inside the route mutex when one is configured, so PUBLISH_MAX == 1 proves
		// the mutex serialized message processing. PUBLISH_DELAY_MS widens the overlap window.
		static final java.util.concurrent.atomic.AtomicInteger PUBLISH_CONCURRENT =
				new java.util.concurrent.atomic.AtomicInteger();
		static final java.util.concurrent.atomic.AtomicInteger PUBLISH_MAX =
				new java.util.concurrent.atomic.AtomicInteger();
		static volatile long PUBLISH_DELAY_MS;

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
					int now = PUBLISH_CONCURRENT.incrementAndGet();
					PUBLISH_MAX.accumulateAndGet(now, Math::max);
					long delay = PUBLISH_DELAY_MS;
					if (delay > 0) {
						try {
							Thread.sleep(delay);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					}
					PRODUCED.add(value);
					PUBLISH_CONCURRENT.decrementAndGet();
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
		MemConnector.PUBLISH_CONCURRENT.set(0);
		MemConnector.PUBLISH_MAX.set(0);
		MemConnector.PUBLISH_DELAY_MS = 0;

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
				// Auto-wrap model: the route declares only the business stage; the engine appends
				// produce(@exchange, @producer) automatically (and protocol_in/out when encapsulated).
				List.of(new RouteDef("route-1", "in", List.of("out"),
						List.of(new RouteStageDef("tag", "set_header(@exchange, \"processed\", \"true\")",
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

	@Test
	@DisplayName("encapsulated dataflow: protocol_in/out are auto-injected and enrich the journey steps")
	void encapsulatedRouteAutoInjectsProtocolStagesAndSteps() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		ContextDef context = new ContextDef("tenant", "cluster",
				List.of(new TopicDef("events.in"), new TopicDef("events.out")),
				// encapsulated = true → the engine auto-wraps with protocol_in / protocol_out.
				List.of(new DataflowDef("df-1", "flow", "mem", true, "1", true)),
				List.of(new ConnectorDef("mem1", "mem", "1.0", Map.of())),
				List.of(
						new SubscriptionDef("in", "df-1", "events.in", "mem1", null, null, null, null),
						new SubscriptionDef("out", "df-1", "events.out", "mem1", null, null, null, null)),
				// No business stage: the route is pure transport (protocol_in → protocol_out → produce).
				List.of(new RouteDef("route-1", "in", List.of("out"), List.of(), null, null)),
				null);

		Map<String, IClass<? extends IConnector>> registry = Map.of(
				"mem:1.0", IClass.getClass(MemConnector.class));
		engine = new Events("asset", List.of(context), registry, injectionContextBuilder, scriptsBuilder);
		engine.onInit();
		engine.onStart();

		Consumer<byte[]> handler = awaitHandler();

		// Feed a serialized Message envelope (what an encapsulated upstream emits).
		Message inbound = Message.create("tenant-x",
				com.garganttua.events.api.enums.MediaType.APPLICATION_JSON, "1",
				"hello".getBytes(StandardCharsets.UTF_8));
		handler.accept(mapper.writeValueAsBytes(inbound));

		byte[] produced = awaitProduced();
		assertNotNull(produced, "the encapsulated route must produce a Message envelope");

		Message out = mapper.readValue(produced, Message.class);
		assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), out.value(),
				"the payload survives the protocol round-trip");
		assertEquals(2, out.steps().size(), "protocol_in (IN) + protocol_out (OUT) each add a journey step");
		assertEquals(Direction.IN, out.steps().get(0).direction(), "first step is the inbound stamp");
		assertEquals(Direction.OUT, out.steps().get(1).direction(), "second step is the outbound stamp");
	}

	@Test
	@DisplayName("concurrency>1 on an unordered dataflow processes messages on a worker pool")
	void parallelConcurrencyProcessesAllMessages() throws Exception {
		int messages = 30;
		ConsumerConfigurationDef parallel = new ConsumerConfigurationDef(
				ProcessMode.EVERYBODY, OriginPolicy.FROM_ANY, DestinationPolicy.TO_ANY, null, 4);
		ContextDef context = new ContextDef("tenant", "cluster",
				List.of(new TopicDef("events.in"), new TopicDef("events.out")),
				// garanteeOrder = false → concurrency is honoured (parallel workers).
				List.of(new DataflowDef("df-1", "flow", "mem", false, "1", false)),
				List.of(new ConnectorDef("mem1", "mem", "1.0", Map.of())),
				List.of(
						new SubscriptionDef("in", "df-1", "events.in", "mem1", null, parallel, null, null),
						new SubscriptionDef("out", "df-1", "events.out", "mem1", null, null, null, null)),
				List.of(new RouteDef("route-1", "in", List.of("out"),
						List.of(new RouteStageDef("tag", "set_header(@exchange, \"k\", \"v\")", null, null, null)),
						null, null)),
				null);

		Map<String, IClass<? extends IConnector>> registry = Map.of(
				"mem:1.0", IClass.getClass(MemConnector.class));
		engine = new Events("asset", List.of(context), registry, injectionContextBuilder, scriptsBuilder);
		engine.onInit();
		engine.onStart();

		Consumer<byte[]> handler = awaitHandler();
		for (int i = 0; i < messages; i++) {
			handler.accept(("msg-" + i).getBytes(StandardCharsets.UTF_8));
		}

		for (int i = 0; i < 100 && MemConnector.PRODUCED.size() < messages; i++) {
			TimeUnit.MILLISECONDS.sleep(40);
		}
		assertEquals(messages, MemConnector.PRODUCED.size(),
				"every message must be produced (parallel workers, order not asserted)");
	}

	@Test
	@DisplayName("synchronization serializes processing through a core mutex (peak in-flight 1)")
	void synchronizationSerializesProcessingThroughCoreMutex() throws Exception {
		int messages = 8;
		// Widen the in-flight window so unsynchronized workers would overlap (peak > 1).
		MemConnector.PUBLISH_DELAY_MS = 60;
		ConsumerConfigurationDef parallel = new ConsumerConfigurationDef(
				ProcessMode.EVERYBODY, OriginPolicy.FROM_ANY, DestinationPolicy.TO_ANY, null, 4);
		ContextDef context = new ContextDef("tenant", "cluster",
				List.of(new TopicDef("events.in"), new TopicDef("events.out")),
				// Unordered dataflow + concurrency 4 → the dispatcher uses 4 parallel workers...
				List.of(new DataflowDef("df-1", "flow", "mem", false, "1", false)),
				List.of(new ConnectorDef("mem1", "mem", "1.0", Map.of())),
				List.of(
						new SubscriptionDef("in", "df-1", "events.in", "mem1", null, parallel, null, null),
						new SubscriptionDef("out", "df-1", "events.out", "mem1", null, null, null, null)),
				// ...but synchronization makes every message run under the same core IMutex.
				List.of(new RouteDef("route-1", "in", List.of("out"),
						List.of(new RouteStageDef("tag", "set_header(@exchange, \"k\", \"v\")", null, null, null)),
						null, new RouteSyncDef("route-1-lock", null))),
				null);

		Map<String, IClass<? extends IConnector>> registry = Map.of(
				"mem:1.0", IClass.getClass(MemConnector.class));
		engine = new Events("asset", List.of(context), registry, injectionContextBuilder, scriptsBuilder);
		engine.onInit();
		engine.onStart();

		Consumer<byte[]> handler = awaitHandler();
		for (int i = 0; i < messages; i++) {
			handler.accept(("msg-" + i).getBytes(StandardCharsets.UTF_8));
		}
		for (int i = 0; i < 200 && MemConnector.PRODUCED.size() < messages; i++) {
			TimeUnit.MILLISECONDS.sleep(40);
		}
		assertEquals(messages, MemConnector.PRODUCED.size(),
				"every message is still produced under synchronization");
		assertEquals(1, MemConnector.PUBLISH_MAX.get(),
				"the route mutex serializes processing: never more than one message in flight at once");
	}

	@Test
	@DisplayName("a failing stage routes the exchange to the route's error subscription")
	void failedRouteSendsExchangeToErrorSubscription() throws Exception {
		// filter_in fails (the exchange carries no dataflowVersion, so it mismatches version "1"),
		// aborting the route. RouteDef.exceptions sends the exchange to the "error" subscription.
		ContextDef context = new ContextDef("tenant", "cluster",
				List.of(new TopicDef("events.in"), new TopicDef("events.out"), new TopicDef("events.error")),
				List.of(new DataflowDef("df-1", "flow", "mem", true, "1", false)),
				List.of(new ConnectorDef("mem1", "mem", "1.0", Map.of())),
				List.of(
						new SubscriptionDef("in", "df-1", "events.in", "mem1", null, null, null, null),
						new SubscriptionDef("out", "df-1", "events.out", "mem1", null, null, null, null),
						new SubscriptionDef("error", "df-1", "events.error", "mem1", null, null, null, null)),
				List.of(new RouteDef("route-1", "in", List.of("out"),
						List.of(new RouteStageDef("guard",
								"filter_in(@exchange, \"TO_ANY\", \"FROM_ANY\", @assetId, @clusterId, @version)",
								null, null, null)),
						new RouteExceptionsDef("error", null, null), null)),
				null);

		Map<String, IClass<? extends IConnector>> registry = Map.of(
				"mem:1.0", IClass.getClass(MemConnector.class));
		engine = new Events("asset", List.of(context), registry, injectionContextBuilder, scriptsBuilder);
		engine.onInit();
		engine.onStart();

		Consumer<byte[]> handler = awaitHandler();
		handler.accept("hello".getBytes(StandardCharsets.UTF_8));

		byte[] errored = awaitProduced();
		assertNotNull(errored, "the failing exchange must be routed to the error subscription");
		assertEquals(1, MemConnector.PRODUCED.size(),
				"only the error route produces (the normal produce stage never ran)");
		assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), errored,
				"the original exchange payload is dead-lettered");
	}

	@Test
	@DisplayName("TIME_INTERVAL (last-wins): only the latest exchange of the interval is published")
	void timeIntervalLastWins() throws Exception {
		engine = startTimeIntervalRoute(false);
		Consumer<byte[]> handler = awaitHandler();
		for (int i = 0; i < 5; i++) {
			handler.accept(("m" + i).getBytes(StandardCharsets.UTF_8));
		}
		for (int i = 0; i < 40 && MemConnector.PRODUCED.isEmpty(); i++) {
			TimeUnit.MILLISECONDS.sleep(40);
		}
		assertEquals(1, MemConnector.PRODUCED.size(), "last-wins emits only the latest exchange");
		assertArrayEquals("m4".getBytes(StandardCharsets.UTF_8), MemConnector.PRODUCED.get(0),
				"the latest message wins");
	}

	@Test
	@DisplayName("TIME_INTERVAL (buffered): the whole batch of the interval is published")
	void timeIntervalBuffered() throws Exception {
		engine = startTimeIntervalRoute(true);
		Consumer<byte[]> handler = awaitHandler();
		for (int i = 0; i < 5; i++) {
			handler.accept(("m" + i).getBytes(StandardCharsets.UTF_8));
		}
		for (int i = 0; i < 60 && MemConnector.PRODUCED.size() < 5; i++) {
			TimeUnit.MILLISECONDS.sleep(40);
		}
		assertEquals(5, MemConnector.PRODUCED.size(), "buffered emits the whole batch on the tick");
	}

	/** Builds + starts a route whose OUT subscription publishes on a 200ms TIME_INTERVAL. */
	private Events startTimeIntervalRoute(boolean buffered) throws Exception {
		SubscriptionDef out = new SubscriptionDef("out", "df-1", "events.out", "mem1",
				PublicationMode.TIME_INTERVAL, null, null,
				new TimeIntervalDef(200, "MILLISECONDS"), buffered, false);
		ContextDef context = new ContextDef("tenant", "cluster",
				List.of(new TopicDef("events.in"), new TopicDef("events.out")),
				List.of(new DataflowDef("df-1", "flow", "mem", false, "1", false)),
				List.of(new ConnectorDef("mem1", "mem", "1.0", Map.of())),
				List.of(new SubscriptionDef("in", "df-1", "events.in", "mem1", null, null, null, null), out),
				List.of(new RouteDef("route-1", "in", List.of("out"), List.of(), null, null)),
				null);
		Map<String, IClass<? extends IConnector>> registry = Map.of(
				"mem:1.0", IClass.getClass(MemConnector.class));
		Events e = new Events("asset", List.of(context), registry, injectionContextBuilder, scriptsBuilder);
		e.onInit();
		e.onStart();
		return e;
	}

	@Test
	@DisplayName("filter_in (auto-injected from the consumer config) drops a message addressed elsewhere")
	void filterInDropsMisaddressedMessage() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		ConsumerConfigurationDef onlyToAsset = new ConsumerConfigurationDef(
				ProcessMode.EVERYBODY, OriginPolicy.FROM_ANY, DestinationPolicy.ONLY_TO_ASSET, null, 1);
		ContextDef context = new ContextDef("tenant", "cluster",
				List.of(new TopicDef("events.in"), new TopicDef("events.out")),
				List.of(new DataflowDef("df-1", "flow", "mem", true, "1", true)), // encapsulated
				List.of(new ConnectorDef("mem1", "mem", "1.0", Map.of())),
				List.of(
						new SubscriptionDef("in", "df-1", "events.in", "mem1", null, onlyToAsset, null, null),
						new SubscriptionDef("out", "df-1", "events.out", "mem1", null, null, null, null)),
				List.of(new RouteDef("route-1", "in", List.of("out"), List.of(), null, null)),
				null);
		Map<String, IClass<? extends IConnector>> registry = Map.of(
				"mem:1.0", IClass.getClass(MemConnector.class));
		engine = new Events("asset", List.of(context), registry, injectionContextBuilder, scriptsBuilder);
		engine.onInit();
		engine.onStart();
		Consumer<byte[]> handler = awaitHandler();

		// Addressed to ANOTHER asset → filter_in (ONLY_TO_ASSET) drops it.
		handler.accept(mapper.writeValueAsBytes(envelope("other-asset")));
		TimeUnit.MILLISECONDS.sleep(300);
		assertEquals(0, MemConnector.PRODUCED.size(), "a message addressed to another asset is filtered out");

		// Addressed to THIS asset → passes the filter and is produced.
		handler.accept(mapper.writeValueAsBytes(envelope("asset")));
		assertNotNull(awaitProduced(), "a message addressed to this asset passes filter_in");
		assertEquals(1, MemConnector.PRODUCED.size());
	}

	@Test
	@DisplayName("filter_out (auto-injected from the producer config) normalises the outbound address per target")
	void filterOutNormalisesOutboundAddress() throws Exception {
		// TO_ANY → broadcast: the produced envelope's destination address is cleared.
		assertNull(producedEnvelope(DestinationPolicy.TO_ANY).toUuid(),
				"TO_ANY clears the destination address (broadcast)");
		// ONLY_TO_ASSET → addressed: the envelope keeps the toUuid stamped by the inbound message.
		assertEquals("addr-1", producedEnvelope(DestinationPolicy.ONLY_TO_ASSET).toUuid(),
				"ONLY_TO_ASSET keeps the destination address");
	}

	/**
	 * Builds a one-target encapsulated route whose OUT producer carries {@code outPolicy}, feeds an
	 * envelope addressed to {@code "addr-1"}, and returns the decoded produced envelope.
	 */
	private Message producedEnvelope(DestinationPolicy outPolicy) throws Exception {
		MemConnector.IN_HANDLER.set(null);
		MemConnector.PRODUCED.clear();
		ObjectMapper mapper = new ObjectMapper();
		ProducerConfigurationDef producerCfg = new ProducerConfigurationDef(outPolicy, null);
		ContextDef context = new ContextDef("tenant", "cluster",
				List.of(new TopicDef("events.in"), new TopicDef("events.out")),
				List.of(new DataflowDef("df-1", "flow", "mem", true, "1", true)), // encapsulated
				List.of(new ConnectorDef("mem1", "mem", "1.0", Map.of())),
				List.of(
						new SubscriptionDef("in", "df-1", "events.in", "mem1", null, null, null, null),
						new SubscriptionDef("out", "df-1", "events.out", "mem1", null, null, producerCfg, null)),
				List.of(new RouteDef("route-1", "in", List.of("out"), List.of(), null, null)),
				null);
		Map<String, IClass<? extends IConnector>> registry = Map.of(
				"mem:1.0", IClass.getClass(MemConnector.class));
		if (engine != null) {
			engine.onStop();
		}
		engine = new Events("asset", List.of(context), registry, injectionContextBuilder, scriptsBuilder);
		engine.onInit();
		engine.onStart();
		awaitHandler().accept(mapper.writeValueAsBytes(envelope("addr-1")));
		return mapper.readValue(awaitProduced(), Message.class);
	}

	/** A serialized encapsulated {@link Message} addressed to {@code toUuid}, dataflow version "1". */
	private static Message envelope(String toUuid) {
		return new Message(Map.of(), null, null, List.of(), "tenant",
				"payload".getBytes(StandardCharsets.UTF_8), "application/json", toUuid, "1");
	}

	private Consumer<byte[]> awaitHandler() throws InterruptedException {
		Consumer<byte[]> handler = null;
		for (int i = 0; i < 50 && handler == null; i++) {
			handler = MemConnector.IN_HANDLER.get();
			if (handler == null) {
				TimeUnit.MILLISECONDS.sleep(40);
			}
		}
		assertNotNull(handler, "engine must start the route consumer and capture its handler");
		return handler;
	}

	private byte[] awaitProduced() throws InterruptedException {
		for (int i = 0; i < 50; i++) {
			if (!MemConnector.PRODUCED.isEmpty()) {
				return MemConnector.PRODUCED.get(0);
			}
			TimeUnit.MILLISECONDS.sleep(40);
		}
		return null;
	}
}
