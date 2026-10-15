package com.garganttua.events.core.perfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.garganttua.core.expression.dsl.ExpressionContextBuilder;
import com.garganttua.core.expression.dsl.IExpressionContextBuilder;
import com.garganttua.core.injection.context.InjectionContext;
import com.garganttua.core.injection.context.dsl.IInjectionContextBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.dsl.IReflectionBuilder;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;
import com.garganttua.core.reflections.ReflectionsAnnotationScanner;
import com.garganttua.core.runtime.dsl.IRuntimesBuilder;
import com.garganttua.core.runtime.dsl.RuntimesBuilder;
import com.garganttua.core.script.dsl.IScriptsBuilder;
import com.garganttua.core.script.dsl.ScriptsBuilder;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.Message;
import com.garganttua.events.api.context.ConnectorDef;
import com.garganttua.events.api.context.ConsumerConfigurationDef;
import com.garganttua.events.api.context.ContextDef;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.RouteDef;
import com.garganttua.events.api.context.RouteStageDef;
import com.garganttua.events.api.context.SubscriptionDef;
import com.garganttua.events.api.context.TopicDef;
import com.garganttua.events.api.enums.DestinationPolicy;
import com.garganttua.events.api.enums.MediaType;
import com.garganttua.events.api.enums.OriginPolicy;
import com.garganttua.events.api.enums.ProcessMode;
import com.garganttua.events.core.Events;

/**
 * What it costs to route a message, with the bus replaced by {@link MockBusConnector}, which hands
 * messages over by method call.
 *
 * <p>
 * Every microsecond measured belongs to the engine: the bridge from the consumer, the route's
 * workflow, its stage scripts, the protocol envelope when the dataflow is encapsulated, and the
 * produce step. A measurement against Kafka would add a broker and a network to that and answer a
 * different question.
 * </p>
 *
 * <p>
 * On a sequential dataflow the engine runs the route on the thread that delivers the message, so
 * the duration of a {@code handler.accept(bytes)} is the duration of the route. The parallel
 * dataflow is measured by throughput instead, since its handler only hands the message to a worker.
 * </p>
 *
 * <h2>What is asserted</h2>
 * <p>
 * As in api-core's performance suite, no absolute threshold, only relations that hold on any
 * machine, measured on the floor and with the scenarios interleaved (see {@link PerfHarness}):
 * a stage costs about the same whether it is the fourth or the sixteenth; a route with four
 * targets does not cost four routes; routing the ten-thousandth message costs what routing the
 * first did. And two correctness properties that only show under volume: a parallel route delivers
 * every message exactly once, an ordered one delivers them in order.
 * </p>
 *
 * <pre>{@code
 * mvn -o test -pl :garganttua-events-core -Dtest='RoutePerfTest' -Dgarganttua.perf=true -Djacoco.skip=true
 * }</pre>
 */
@EnabledIfSystemProperty(named = "garganttua.perf", matches = "true",
		disabledReason = "performance measurement — opt in with -Dgarganttua.perf=true -Djacoco.skip=true")
@DisplayName("Route cost (mocked bus)")
class RoutePerfTest {

	private static final int WARMUP = Integer.getInteger("garganttua.perf.warmup", 2_000);
	private static final int RUNS = Integer.getInteger("garganttua.perf.runs", 5_000);
	private static final int VOLUME = Integer.getInteger("garganttua.perf.volume", 20_000);
	private static final int CORES = Runtime.getRuntime().availableProcessors();
	private static final int WORKERS = Math.max(2, Math.min(4, CORES));

	private static final String SEQUENTIAL = "df-seq";
	private static final String ENCAPSULATED = "df-enc";
	private static final String PARALLEL = "df-par";
	private static final String ORDERED = "df-ord";

	private IInjectionContextBuilder injectionContextBuilder;
	private IScriptsBuilder scriptsBuilder;
	private Events engine;
	private final List<PerfHarness.Stats> collected = new ArrayList<>();
	private final List<String> notes = new ArrayList<>();

	@BeforeEach
	void setUp() {
		MockBusConnector.reset();
		IReflectionBuilder reflectionBuilder = ReflectionBuilder.builder()
				.withProvider(new RuntimeReflectionProvider())
				.withScanner(new ReflectionsAnnotationScanner());
		IClass.setReflection(reflectionBuilder.build());

		this.injectionContextBuilder = InjectionContext.builder()
				.provide(reflectionBuilder)
				.autoDetect(true)
				.withPackage("com.garganttua");
		IExpressionContextBuilder expressionContextBuilder = ExpressionContextBuilder.builder();
		expressionContextBuilder.withPackage("com.garganttua").autoDetect(true)
				.provide(this.injectionContextBuilder);
		this.injectionContextBuilder.build().onInit().onStart();
		expressionContextBuilder.build();
		IRuntimesBuilder runtimesBuilder = RuntimesBuilder.builder().provide(this.injectionContextBuilder);
		this.scriptsBuilder = ScriptsBuilder.builder()
				.provide(this.injectionContextBuilder)
				.provide(expressionContextBuilder)
				.provide(runtimesBuilder);
	}

	@AfterEach
	void tearDown() throws Exception {
		if (this.engine != null) {
			this.engine.onStop();
			this.engine.onFlush();
		}
		IClass.setReflection(null);
		if (!this.collected.isEmpty() || !this.notes.isEmpty()) {
			System.out.println(report());
		}
	}

	// ───── topology ─────

	/** One route: {@code <id>.in} → {@code stages} × {@code set_header} → {@code <id>.out.0 … .out.(targets-1)}. */
	private record Route(String id, String dataflow, int stages, int targets, int concurrency) {

		String in() {
			return this.id + ".in";
		}

		String out(int target) {
			return this.id + ".out." + target;
		}
	}

	private static Route route(String id, String dataflow, int stages) {
		return new Route(id, dataflow, stages, 1, 1);
	}

	/** Starts one engine carrying every given route, all on the mock bus. */
	private void start(Route... routes) throws Exception {
		List<TopicDef> topics = new ArrayList<>();
		List<SubscriptionDef> subscriptions = new ArrayList<>();
		List<RouteDef> routeDefs = new ArrayList<>();
		for (Route route : routes) {
			ConsumerConfigurationDef consumer = new ConsumerConfigurationDef(
					ProcessMode.EVERYBODY, OriginPolicy.FROM_ANY, DestinationPolicy.TO_ANY, null, route.concurrency());
			topics.add(new TopicDef(route.in()));
			subscriptions.add(new SubscriptionDef(route.in(), route.dataflow(), route.in(), "bus", null, consumer, null, null));
			List<String> targets = new ArrayList<>();
			for (int t = 0; t < route.targets(); t++) {
				topics.add(new TopicDef(route.out(t)));
				subscriptions.add(new SubscriptionDef(route.out(t), route.dataflow(), route.out(t), "bus", null, null, null, null));
				targets.add(route.out(t));
			}
			List<RouteStageDef> stages = new ArrayList<>();
			for (int s = 0; s < route.stages(); s++) {
				stages.add(new RouteStageDef("stage-" + s,
						"set_header(@exchange, \"h" + s + "\", \"v\")", null, null, null));
			}
			routeDefs.add(new RouteDef(route.id(), route.in(), targets, stages, null, null));
		}
		ContextDef context = new ContextDef("tenant", "cluster", topics,
				List.of(new DataflowDef(SEQUENTIAL, "sequential", "mockbus", true, "1", false),
						new DataflowDef(ENCAPSULATED, "encapsulated", "mockbus", true, "1", true),
						new DataflowDef(PARALLEL, "parallel", "mockbus", false, "1", false),
						new DataflowDef(ORDERED, "ordered", "mockbus", true, "1", false)),
				List.of(new ConnectorDef("bus", "mockbus", "1.0", Map.of())),
				subscriptions, routeDefs, null);

		Map<String, IClass<? extends IConnector>> registry = Map.of(
				"mockbus:1.0", IClass.getClass(MockBusConnector.class));
		this.engine = new Events("asset", List.of(context), registry, this.injectionContextBuilder, this.scriptsBuilder);
		this.engine.onInit();
		this.engine.onStart();
	}

	/** The handler the engine registered for {@code route}, once its consumer thread has started. */
	private static Consumer<byte[]> feed(Route route) throws InterruptedException {
		for (int i = 0; i < 250; i++) {
			var handler = MockBusConnector.subscriber(route.in());
			if (handler.isPresent()) {
				return handler.get();
			}
			TimeUnit.MILLISECONDS.sleep(20);
		}
		throw new AssertionError("the engine never started the consumer of " + route.in());
	}

	private static byte[] payload(int i) {
		return ("message-" + i).getBytes(StandardCharsets.UTF_8);
	}

	/** Waits until {@code topic} has received {@code count} messages; false on timeout. */
	private static boolean awaitPublished(String topic, long count, long timeoutMillis) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (MockBusConnector.published(topic) < count) {
			if (System.currentTimeMillis() > deadline) {
				return false;
			}
			TimeUnit.MILLISECONDS.sleep(5);
		}
		return true;
	}

	// ─────────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("the floor: a message through a route with no business stage")
	void transportFloor() throws Exception {
		Route bare = route("bare", SEQUENTIAL, 0);
		start(bare);
		Consumer<byte[]> handler = feed(bare);
		byte[] message = payload(0);

		record(PerfHarness.measure("route — no stage", WARMUP, RUNS, i -> handler.accept(message)));

		assertEquals(WARMUP + RUNS, MockBusConnector.published(bare.out(0)),
				"every message fed was produced — the measure timed the whole route");
	}

	@Test
	@DisplayName("a stage costs about the same whether it is the 4th or the 16th")
	void stagesScaleLinearly() throws Exception {
		Route four = route("four", SEQUENTIAL, 4);
		Route sixteen = route("sixteen", SEQUENTIAL, 16);
		start(four, sixteen);
		Consumer<byte[]> toFour = feed(four);
		Consumer<byte[]> toSixteen = feed(sixteen);
		byte[] message = payload(0);

		PerfHarness.Stats[] both = PerfHarness.compare(
				"route — 4 stages", i -> toFour.accept(message),
				"route — 16 stages", i -> toSixteen.accept(message),
				WARMUP, RUNS);
		PerfHarness.Stats small = record(both[0]);
		PerfHarness.Stats large = record(both[1]);

		this.notes.add(String.format(Locale.ROOT, "one set_header stage costs about %.1f us",
				(large.minUs() - small.minUs()) / 12));
		// Four times the stages, plus the same transport: a ratio at most 4. A stage whose cost
		// grows with the number of stages before it (an exchange copied whole at each step, a
		// variable scope that is never popped) pushes it well past.
		assertTrue(large.minUs() < small.minUs() * 6,
				() -> "16 stages cost " + ratio(large, small) + "x 4 stages — a stage costs more the "
						+ "further down the route it runs." + report());
	}

	@Test
	@DisplayName("the protocol envelope of an encapsulated dataflow")
	void envelopeCost() throws Exception {
		Route raw = route("raw", SEQUENTIAL, 1);
		Route wrapped = route("wrapped", ENCAPSULATED, 1);
		start(raw, wrapped);
		Consumer<byte[]> toRaw = feed(raw);
		Consumer<byte[]> toWrapped = feed(wrapped);
		byte[] message = payload(0);
		byte[] envelope = new ObjectMapper().writeValueAsBytes(
				Message.create("tenant", MediaType.APPLICATION_JSON, "1", message));

		PerfHarness.Stats[] both = PerfHarness.compare(
				"route — raw bytes", i -> toRaw.accept(message),
				"route — encapsulated envelope", i -> toWrapped.accept(envelope),
				WARMUP, RUNS);
		PerfHarness.Stats bare = record(both[0]);
		PerfHarness.Stats enveloped = record(both[1]);

		assertEquals(WARMUP + RUNS, MockBusConnector.published(wrapped.out(0)),
				"every envelope passed protocol_in and was produced");
		this.notes.add(String.format(Locale.ROOT, "reading and rewriting the envelope adds %.1f us",
				enveloped.minUs() - bare.minUs()));
	}

	@Test
	@DisplayName("a route with 4 targets does not cost 4 routes")
	void fanOutSharesTheRoute() throws Exception {
		Route one = new Route("one", SEQUENTIAL, 2, 1, 1);
		Route four = new Route("fan", SEQUENTIAL, 2, 4, 1);
		start(one, four);
		Consumer<byte[]> toOne = feed(one);
		Consumer<byte[]> toFour = feed(four);
		byte[] message = payload(0);

		PerfHarness.Stats[] both = PerfHarness.compare(
				"route — 1 target", i -> toOne.accept(message),
				"route — 4 targets", i -> toFour.accept(message),
				WARMUP, RUNS);
		PerfHarness.Stats single = record(both[0]);
		PerfHarness.Stats fanned = record(both[1]);

		for (int t = 0; t < 4; t++) {
			assertEquals(WARMUP + RUNS, MockBusConnector.published(four.out(t)), "target " + t + " got every message");
		}
		// The stages run once; only the produce step is repeated per target.
		assertTrue(fanned.minUs() < single.minUs() * 3,
				() -> "4 targets cost " + ratio(fanned, single) + "x 1 target — the route runs again "
						+ "for each target." + report());
	}

	@Test
	@DisplayName("routing the 10,000th message costs what routing the first did")
	void costDoesNotDrift() throws Exception {
		Route route = route("drift", SEQUENTIAL, 2);
		start(route);
		Consumer<byte[]> handler = feed(route);

		PerfHarness.Stats stats = record(PerfHarness.measure("route — drift over " + (RUNS * 2) + " runs",
				WARMUP, RUNS * 2, i -> handler.accept(payload(i))));

		assertTrue(stats.driftRatio() < 3.0, () -> String.format(Locale.ROOT,
				"the last messages cost %.2fx the first — routing makes the next message more expensive.%s",
				stats.driftRatio(), report()));
	}

	@Test
	@DisplayName("a parallel route delivers every message exactly once, and faster than a sequential one")
	void parallelThroughput() throws Exception {
		MockBusConnector.reset(true);
		Route sequential = new Route("seq", SEQUENTIAL, 2, 1, 1);
		Route parallel = new Route("par", PARALLEL, 2, 1, WORKERS);
		start(sequential, parallel);
		Consumer<byte[]> toSequential = feed(sequential);
		Consumer<byte[]> toParallel = feed(parallel);
		for (int i = 0; i < WARMUP; i++) {
			toSequential.accept(payload(i));
			toParallel.accept(payload(i));
		}
		assertTrue(awaitPublished(parallel.out(0), WARMUP, 60_000), "warm-up drained");

		long start = System.nanoTime();
		for (int i = 0; i < VOLUME; i++) {
			toSequential.accept(payload(WARMUP + i));
		}
		double sequentialSeconds = (System.nanoTime() - start) / 1e9d;

		start = System.nanoTime();
		for (int i = 0; i < VOLUME; i++) {
			toParallel.accept(payload(WARMUP + i));
		}
		assertTrue(awaitPublished(parallel.out(0), WARMUP + VOLUME, 120_000),
				"the parallel route delivered " + MockBusConnector.published(parallel.out(0)) + " of "
						+ (WARMUP + VOLUME));
		double parallelSeconds = (System.nanoTime() - start) / 1e9d;

		Set<String> distinct = new HashSet<>();
		for (byte[] produced : MockBusConnector.kept(parallel.out(0))) {
			distinct.add(new String(produced, StandardCharsets.UTF_8));
		}
		assertEquals(WARMUP + VOLUME, MockBusConnector.published(parallel.out(0)), "no message produced twice");
		assertEquals(WARMUP + VOLUME, distinct.size(), "no message lost, none duplicated");

		double speedup = sequentialSeconds / parallelSeconds;
		this.notes.add(String.format(Locale.ROOT,
				"%d messages: sequential %.0f msg/s, %d workers %.0f msg/s (%.2fx, %d cores)",
				VOLUME, VOLUME / sequentialSeconds, WORKERS, VOLUME / parallelSeconds, speedup, CORES));
		assumeFalse(PerfHarness.coverageAgentAttached(),
				"JaCoCo instruments this JVM: its shared probes make workers contend, the speedup "
						+ "would measure the agent — rerun with -Djacoco.skip=true");
		if (CORES >= 2) {
			assertTrue(speedup >= 1.0, () -> String.format(Locale.ROOT,
					"%d workers route %.2fx as fast as one thread — the workers queue on something shared.%s",
					WORKERS, speedup, report()));
		}
	}

	@Test
	@DisplayName("an ordered dataflow keeps the order under volume, even when concurrency is asked for")
	void orderedRouteKeepsOrder() throws Exception {
		MockBusConnector.reset(true);
		Route ordered = new Route("ord", ORDERED, 2, 1, WORKERS);
		start(ordered);
		Consumer<byte[]> handler = feed(ordered);

		long start = System.nanoTime();
		for (int i = 0; i < VOLUME; i++) {
			handler.accept(payload(i));
		}
		double seconds = (System.nanoTime() - start) / 1e9d;

		List<byte[]> produced = MockBusConnector.kept(ordered.out(0));
		assertEquals(VOLUME, produced.size());
		for (int i = 0; i < VOLUME; i++) {
			assertEquals("message-" + i, new String(produced.get(i), StandardCharsets.UTF_8),
					"message " + i + " left out of order");
		}
		this.notes.add(String.format(Locale.ROOT, "ordered route: %d messages in order, %.0f msg/s",
				VOLUME, VOLUME / seconds));
	}

	// ─────────────────────────────────────────────────────────────────────────

	private PerfHarness.Stats record(PerfHarness.Stats stats) {
		this.collected.add(stats);
		return stats;
	}

	private static String ratio(PerfHarness.Stats a, PerfHarness.Stats b) {
		return String.format(Locale.ROOT, "%.1f", a.minUs() / Math.max(b.minUs(), 0.001d));
	}

	private String report() {
		StringBuilder sb = new StringBuilder(PerfHarness.report("Route cost, mocked bus:", this.collected));
		this.notes.forEach(n -> sb.append(n).append(System.lineSeparator()));
		return sb.toString();
	}
}
