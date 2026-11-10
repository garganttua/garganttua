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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import com.garganttua.events.api.IAcknowledgingConsumer;
import com.garganttua.events.api.context.QualityOfService;
import com.garganttua.events.api.exceptions.ConnectorException;
import com.garganttua.events.core.context.JsonContextReader;

/**
 * Quality of service end to end: when the engine starts a consumer with acknowledgement, what "safe" means,
 * the attempts a route makes before its error subscription, and the refusal of a promise a connector cannot keep.
 */
@DisplayName("Quality of service — acknowledged after the route, attempts, refusal")
class EventsQualityOfServiceE2ETest {

	/**
	 * An in-memory connector that CAN acknowledge after the route: its consumer captures the handler it is started
	 * with; its producers fail on demand — the normal one a number of times, the error one always or never.
	 */
	@Connector(type = "ackmem", version = "1.0")
	public static final class AckConnector extends AbstractLifecycle implements IConnector {

		static final AtomicReference<Function<byte[], CompletionStage<Boolean>>> ACK_HANDLER = new AtomicReference<>();
		static final AtomicReference<Consumer<byte[]>> PLAIN_HANDLER = new AtomicReference<>();
		static final AtomicInteger OUT_FAILURES_LEFT = new AtomicInteger();
		static final AtomicInteger OUT_CALLS = new AtomicInteger();
		static volatile boolean errorFails;
		static final List<byte[]> OUT = new CopyOnWriteArrayList<>();
		static final List<byte[]> ERRORS = new CopyOnWriteArrayList<>();

		static void reset() {
			ACK_HANDLER.set(null);
			PLAIN_HANDLER.set(null);
			OUT_FAILURES_LEFT.set(0);
			OUT_CALLS.set(0);
			errorFails = false;
			OUT.clear();
			ERRORS.clear();
		}

		public AckConnector() {
			// public no-arg ctor for the reflective registry path
		}

		@Override
		public IReflection reflection() {
			return IClass.getReflection();
		}

		@Override
		public String getName() {
			return "ackmem";
		}

		@Override
		public void configure(Map<String, String> configuration, ConnectorContext ctx) {
			// no-op
		}

		@Override
		public IConsumer createConsumer(SubscriptionDef sub, DataflowDef df) {
			return new IAcknowledgingConsumer() {
				@Override
				public void start(Consumer<byte[]> messageHandler) {
					PLAIN_HANDLER.set(messageHandler);
				}

				@Override
				public void startAcknowledged(Function<byte[], CompletionStage<Boolean>> messageHandler) {
					ACK_HANDLER.set(messageHandler);
				}

				@Override
				public void stop() {
					// no-op
				}
			};
		}

		@Override
		public IProducer createProducer(SubscriptionDef sub, DataflowDef df) {
			boolean error = "events.error".equals(sub.topic());
			return new IProducer() {
				@Override
				public void publish(byte[] value) throws ConnectorException {
					if (error) {
						if (errorFails) {
							throw new ConnectorException("error store down");
						}
						ERRORS.add(value);
						return;
					}
					OUT_CALLS.incrementAndGet();
					if (OUT_FAILURES_LEFT.getAndDecrement() > 0) {
						throw new ConnectorException("broker down");
					}
					OUT.add(value);
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
		AckConnector.reset();
		EventsRouteE2ETest.MemConnector.IN_HANDLER.set(null);

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

	private static ContextDef context(String connectorType, QualityOfService qos, RouteExceptionsDef exceptions) {
		ConsumerConfigurationDef consumer = new ConsumerConfigurationDef(ProcessMode.EVERYBODY, OriginPolicy.FROM_ANY,
				DestinationPolicy.TO_ANY, null, 1, qos);
		return new ContextDef("tenant", "cluster",
				List.of(new TopicDef("events.in"), new TopicDef("events.out"), new TopicDef("events.error")),
				List.of(new DataflowDef("df-1", "flow", connectorType, true, "1", false)),
				List.of(new ConnectorDef("c1", connectorType, "1.0", Map.of())),
				List.of(
						new SubscriptionDef("in", "df-1", "events.in", "c1", null, consumer, null, null),
						new SubscriptionDef("out", "df-1", "events.out", "c1", null, null, null, null),
						new SubscriptionDef("error", "df-1", "events.error", "c1", null, null, null, null)),
				List.of(new RouteDef("route-1", "in", List.of("out"),
						List.of(new RouteStageDef("tag", "set_header(@exchange, \"processed\", \"true\")",
								null, null, null)),
						exceptions, null)),
				null);
	}

	private void startEngine(ContextDef context) throws Exception {
		Map<String, IClass<? extends IConnector>> registry = Map.of(
				"ackmem:1.0", IClass.getClass(AckConnector.class),
				"mem:1.0", IClass.getClass(EventsRouteE2ETest.MemConnector.class));
		engine = new Events("asset", List.of(context), registry, injectionContextBuilder, scriptsBuilder);
		engine.onInit();
		engine.onStart();
	}

	private static <T> T await(AtomicReference<T> captured) throws InterruptedException {
		for (int i = 0; i < 100 && captured.get() == null; i++) {
			TimeUnit.MILLISECONDS.sleep(20);
		}
		return captured.get();
	}

	private static boolean feed(String value) throws Exception {
		Function<byte[], CompletionStage<Boolean>> handler = await(AckConnector.ACK_HANDLER);
		assertNotNull(handler, "the engine starts the consumer with acknowledgement");
		return handler.apply(value.getBytes(StandardCharsets.UTF_8)).toCompletableFuture().get(10, TimeUnit.SECONDS);
	}

	@Test
	@DisplayName("a transient failure is retried in place: the message succeeds, and never reaches the error subscription")
	void attempts() throws Exception {
		startEngine(context("ackmem", QualityOfService.AT_LEAST_ONCE, new RouteExceptionsDef("error", null, null, 3)));
		AckConnector.OUT_FAILURES_LEFT.set(2);

		assertTrue(feed("hello"), "safe: the third attempt succeeded");

		assertEquals(3, AckConnector.OUT_CALLS.get());
		assertEquals(1, AckConnector.OUT.size());
		assertTrue(AckConnector.ERRORS.isEmpty());
	}

	@Test
	@DisplayName("still failing after its attempts, a message the error subscription took is SAFE: acknowledged, not redelivered")
	void deadLettered() throws Exception {
		startEngine(context("ackmem", QualityOfService.AT_LEAST_ONCE, new RouteExceptionsDef("error", null, null, 2)));
		AckConnector.OUT_FAILURES_LEFT.set(100);

		assertTrue(feed("hello"));

		assertEquals(2, AckConnector.OUT_CALLS.get(), "two attempts, then the error subscription");
		assertEquals(1, AckConnector.ERRORS.size());
	}

	@Test
	@DisplayName("a message nowhere — no error subscription, or it failed too — is NOT safe: the transport gives it back")
	void nowhere() throws Exception {
		startEngine(context("ackmem", QualityOfService.AT_LEAST_ONCE, null));
		AckConnector.OUT_FAILURES_LEFT.set(100);
		assertFalse(feed("no error subscription"));

		engine.onStop();
		engine.onFlush();
		AckConnector.reset();
		startEngine(context("ackmem", QualityOfService.AT_LEAST_ONCE, new RouteExceptionsDef("error", null, null)));
		AckConnector.OUT_FAILURES_LEFT.set(100);
		AckConnector.errorFails = true;
		assertFalse(feed("error subscription down"));
	}

	@Test
	@DisplayName("AT_MOST_ONCE declared: started without acknowledgement, even by a connector that could")
	void atMostOnce() throws Exception {
		startEngine(context("ackmem", QualityOfService.AT_MOST_ONCE, null));

		assertNotNull(await(AckConnector.PLAIN_HANDLER), "started on receipt");
		assertNull(AckConnector.ACK_HANDLER.get());
	}

	@Test
	@DisplayName("not declared: a connector that can acknowledge does, as before")
	void undeclared() throws Exception {
		startEngine(context("ackmem", null, null));

		assertNotNull(await(AckConnector.ACK_HANDLER));
	}

	@Test
	@DisplayName("AT_LEAST_ONCE on a connector that cannot acknowledge after the route: the engine refuses the route")
	void refused() {
		Exception refused = assertThrows(Exception.class,
				() -> startEngine(context("mem", QualityOfService.AT_LEAST_ONCE, null)));
		Throwable cause = refused;
		StringBuilder messages = new StringBuilder();
		while (cause != null) {
			messages.append(cause.getMessage()).append(" | ");
			cause = cause.getCause();
		}
		assertTrue(messages.toString().contains("AT_LEAST_ONCE"), messages.toString());
		engine = null;
	}

	@Test
	@DisplayName("a JSON context declares both: qualityOfService on the consumer, attempts on the exceptions")
	void json() throws Exception {
		ContextDef context = JsonContextReader.readFromString("""
				{"tenantId":"t","clusterId":"c",
				 "subscriptions":[{"id":"in","dataflow":"df","topic":"x","connector":"c1",
				   "consumerConfiguration":{"qualityOfService":"AT_LEAST_ONCE"}}],
				 "routes":[{"uuid":"r","from":"in","to":[],"exceptions":{"to":"err","attempts":4}}]}
				""");

		assertEquals(QualityOfService.AT_LEAST_ONCE,
				context.subscriptions().get(0).consumerConfiguration().qualityOfService());
		assertEquals(4, context.routes().get(0).exceptions().attempts());
	}
}
