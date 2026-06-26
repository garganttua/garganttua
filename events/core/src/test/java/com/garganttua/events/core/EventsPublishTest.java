package com.garganttua.events.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.core.lifecycle.AbstractLifecycle;
import com.garganttua.core.lifecycle.ILifecycle;
import com.garganttua.core.lifecycle.LifecycleException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;
import com.garganttua.events.api.ConnectorContext;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.connectors.annotations.Connector;
import com.garganttua.events.api.context.ConnectorDef;
import com.garganttua.events.api.context.ContextDef;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.SubscriptionDef;
import com.garganttua.events.api.context.TopicDef;
import com.garganttua.events.api.exceptions.ConnectorException;
import com.garganttua.events.api.exceptions.EventsException;

/**
 * Exercises the programmatic publication API ({@link Events#publish(String, byte[])} /
 * {@link Events#producer(String)}) against a started engine wired with an in-memory capture
 * connector. Asserts that publication reuses the engine's own connector instance (no duplicate),
 * memoises the producer per subscription, surfaces a clear {@link EventsException} on unknown
 * topic/subscription, and that ad-hoc producers are stopped on engine stop.
 *
 * <p>The full consumer&rarr;route&rarr;produce e2e is intentionally reduced to publish-resolution +
 * isolation here: building a real route workflow requires live injection/expression context
 * builders, which would make a unit test heavy and flaky. The capture connector instead lets us
 * verify the publish path lands on the engine's connector exactly.</p>
 */
@DisplayName("Events programmatic publish API")
class EventsPublishTest {

	/** Records published payloads and counts produced producers; the engine's own connector. */
	@Connector(type = "capture", version = "1.0")
	public static final class CaptureConnector extends AbstractLifecycle implements IConnector {

		final List<byte[]> published = new CopyOnWriteArrayList<>();
		final List<CaptureProducer> producers = new ArrayList<>();

		public CaptureConnector() {
			// public no-arg ctor so the engine's reflective registry path can instantiate it
		}

		@Override
		public IReflection reflection() {
			return IClass.getReflection();
		}

		@Override
		public String getName() {
			return "capture";
		}

		@Override
		public void configure(Map<String, String> configuration, ConnectorContext ctx) {
			// no-op stub
		}

		@Override
		public IConsumer createConsumer(SubscriptionDef sub, DataflowDef df) {
			return new IConsumer() {
				@Override
				public void start(Consumer<byte[]> messageHandler) {
					// no-op stub
				}

				@Override
				public void stop() {
					// no-op stub
				}
			};
		}

		@Override
		public IProducer createProducer(SubscriptionDef sub, DataflowDef df) {
			CaptureProducer producer = new CaptureProducer(this);
			this.producers.add(producer);
			return producer;
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

	/** Producer recording into its parent connector and tracking its own stop. */
	static final class CaptureProducer implements IProducer {
		private final CaptureConnector connector;
		volatile boolean stopped;

		CaptureProducer(CaptureConnector connector) {
			this.connector = connector;
		}

		@Override
		public void publish(byte[] value) throws ConnectorException {
			this.connector.published.add(value);
		}

		@Override
		public void stop() throws ConnectorException {
			this.stopped = true;
		}
	}

	private Events engine;

	@BeforeEach
	void setUp() throws Exception {
		IClass.setReflection(ReflectionBuilder.builder()
				.withProvider(new RuntimeReflectionProvider())
				.build());

		TopicDef topic = new TopicDef("orders.out");
		ConnectorDef connDef = new ConnectorDef("capture1", "capture", "1.0", Map.of());
		DataflowDef df = new DataflowDef("df-1", "orders", "capture", true, "1", false);
		SubscriptionDef sub = new SubscriptionDef("out", "df-1", "orders.out", "capture1",
				null, null, null, null);
		ContextDef context = new ContextDef("default", "main",
				List.of(topic), List.of(df), List.of(connDef),
				List.of(sub), null, null);

		Map<String, IClass<? extends IConnector>> registry = Map.of(
				"capture:1.0", IClass.getClass(CaptureConnector.class));
		this.engine = new Events("asset-1", List.of(context), registry, null, null);
		this.engine.onInit();
		this.engine.onStart();
	}

	@AfterEach
	void tearDown() throws Exception {
		if (this.engine != null) {
			this.engine.onStop();
			this.engine.onFlush();
		}
		IClass.setReflection(null);
	}

	@Nested
	@DisplayName("publish(topic, payload)")
	class Publish {

		@Test
		@DisplayName("routes the payload onto the engine's own connector")
		void publishesViaEngineConnector() throws Exception {
			IProducer producer = engine.producer("out");
			CaptureConnector engineConnector = ((CaptureProducer) producer).connector;

			engine.publish("orders.out", "hello".getBytes(StandardCharsets.UTF_8));

			assertEquals(1, engineConnector.published.size());
			assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), engineConnector.published.get(0));
		}

		@Test
		@DisplayName("does not instantiate a duplicate connector (single producer memoised)")
		void memoisesProducer() throws Exception {
			IProducer first = engine.producer("out");
			CaptureConnector engineConnector = ((CaptureProducer) first).connector;

			engine.publish("orders.out", "a".getBytes(StandardCharsets.UTF_8));
			engine.publish("orders.out", "b".getBytes(StandardCharsets.UTF_8));
			IProducer second = engine.producer("out");

			assertSame(first, second, "producer must be memoised per subscription");
			assertEquals(1, engineConnector.producers.size(), "only one producer created");
			assertEquals(2, engineConnector.published.size());
		}

		@Test
		@DisplayName("throws EventsException naming the unknown topic")
		void unknownTopic() {
			EventsException ex = assertThrows(EventsException.class,
					() -> engine.publish("ghost.topic", new byte[0]));
			assertTrue(ex.getMessage().contains("ghost.topic"), "message echoes the topic");
		}
	}

	@Nested
	@DisplayName("producer(subscriptionId)")
	class Producer {

		@Test
		@DisplayName("returns a reusable producer for a known subscription")
		void knownSubscription() throws Exception {
			IProducer producer = engine.producer("out");
			assertSame(producer, engine.producer("out"), "same producer per subscription id");
		}

		@Test
		@DisplayName("throws EventsException naming the unknown subscription")
		void unknownSubscription() {
			EventsException ex = assertThrows(EventsException.class,
					() -> engine.producer("nope"));
			assertTrue(ex.getMessage().contains("nope"), "message echoes the subscription id");
		}
	}

	@Test
	@DisplayName("ad-hoc producers are stopped when the engine stops")
	void producersStoppedOnStop() throws Exception {
		CaptureProducer producer = (CaptureProducer) engine.producer("out");
		assertEquals(false, producer.stopped);

		engine.onStop();
		engine.onFlush();
		this.engine = null; // already stopped; prevent tearDown double-stop

		assertTrue(producer.stopped, "ad-hoc producer must be stopped on engine stop");
	}
}
