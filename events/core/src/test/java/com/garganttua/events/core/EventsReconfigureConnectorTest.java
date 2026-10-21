package com.garganttua.events.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import com.garganttua.events.api.IReconfigurableConnector;
import com.garganttua.events.api.connectors.annotations.Connector;
import com.garganttua.events.api.context.ConnectorDef;
import com.garganttua.events.api.context.ContextDef;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.TopicDef;
import com.garganttua.events.api.exceptions.ConnectorException;
import com.garganttua.events.api.exceptions.EventsException;

/** {@link Events#reconfigureConnector}: reaches the declared connector instances, refuses clearly otherwise. */
@DisplayName("Events.reconfigureConnector")
class EventsReconfigureConnectorTest {

	/** Records every configuration it receives; refuses a "bad" key like a real connector refuses a bad value. */
	@Connector(type = "tunable", version = "1.0")
	public static final class TunableConnector extends AbstractLifecycle implements IReconfigurableConnector {

		static final List<TunableConnector> INSTANCES = new CopyOnWriteArrayList<>();
		final List<Map<String, String>> reconfigurations = new CopyOnWriteArrayList<>();

		public TunableConnector() {
			INSTANCES.add(this);
		}

		@Override
		public void reconfigure(Map<String, String> configuration) throws ConnectorException {
			if (configuration.containsKey("bad")) {
				throw new ConnectorException("bad configuration");
			}
			reconfigurations.add(Map.copyOf(configuration));
		}

		@Override
		public IReflection reflection() {
			return IClass.getReflection();
		}

		@Override
		public String getName() {
			return "tunable";
		}

		@Override
		public void configure(Map<String, String> configuration, ConnectorContext ctx) {
			// nothing to configure
		}

		@Override
		public IConsumer createConsumer(com.garganttua.events.api.context.SubscriptionDef sub, DataflowDef df) {
			return new IConsumer() {
				@Override
				public void start(Consumer<byte[]> handler) {
					// no-op
				}

				@Override
				public void stop() {
					// no-op
				}
			};
		}

		@Override
		public IProducer createProducer(com.garganttua.events.api.context.SubscriptionDef sub, DataflowDef df) {
			return new IProducer() {
				@Override
				public void publish(byte[] value) {
					// no-op
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

	/** Same shape, NOT reconfigurable. */
	@Connector(type = "fixed", version = "1.0")
	public static final class FixedConnector extends AbstractLifecycle implements IConnector {

		public FixedConnector() {
			// reflective instantiation
		}

		@Override
		public IReflection reflection() {
			return IClass.getReflection();
		}

		@Override
		public String getName() {
			return "fixed";
		}

		@Override
		public void configure(Map<String, String> configuration, ConnectorContext ctx) {
			// nothing to configure
		}

		@Override
		public IConsumer createConsumer(com.garganttua.events.api.context.SubscriptionDef sub, DataflowDef df) {
			throw new UnsupportedOperationException();
		}

		@Override
		public IProducer createProducer(com.garganttua.events.api.context.SubscriptionDef sub, DataflowDef df) {
			throw new UnsupportedOperationException();
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

	private Events engine;

	@BeforeEach
	void setUp() throws Exception {
		IClass.setReflection(ReflectionBuilder.builder().withProvider(new RuntimeReflectionProvider()).build());
		TunableConnector.INSTANCES.clear();
		DataflowDef df = new DataflowDef("df-1", "flow", "json", true, "1", false);
		ContextDef main = new ContextDef("default", "main", List.of(new TopicDef("t")), List.of(df),
				List.of(new ConnectorDef("mail", "tunable", "1.0", Map.of()),
						new ConnectorDef("frozen", "fixed", "1.0", Map.of())),
				List.of(), null, null);
		ContextDef other = new ContextDef("default", "other", List.of(new TopicDef("t")), List.of(df),
				List.of(new ConnectorDef("mail", "tunable", "1.0", Map.of())), List.of(), null, null);
		Map<String, IClass<? extends IConnector>> registry = Map.of(
				"tunable:1.0", IClass.getClass(TunableConnector.class),
				"fixed:1.0", IClass.getClass(FixedConnector.class));
		engine = new Events("asset-1", List.of(main, other), registry, null, null);
		engine.onInit();
		engine.onStart();
	}

	@AfterEach
	void tearDown() throws Exception {
		engine.onStop();
		engine.onFlush();
		IClass.setReflection(null);
	}

	@Test
	@DisplayName("the targeted form reaches the connector of that context only")
	void targeted() throws Exception {
		engine.reconfigureConnector("default", "main", "mail", Map.of("host", "smtp.example"));

		assertEquals(2, TunableConnector.INSTANCES.size());
		long reached = TunableConnector.INSTANCES.stream().filter(c -> !c.reconfigurations.isEmpty()).count();
		assertEquals(1, reached);
	}

	@Test
	@DisplayName("the broadcast form reaches every context that declares the connector")
	void broadcast() throws Exception {
		assertEquals(2, engine.reconfigureConnector("mail", Map.of("host", "smtp.example")));
		assertTrue(TunableConnector.INSTANCES.stream().allMatch(c -> c.reconfigurations.size() == 1));
	}

	@Test
	@DisplayName("unknown context, unknown connector, non-reconfigurable connector: a clear refusal")
	void refusals() {
		EventsException noContext = assertThrows(EventsException.class,
				() -> engine.reconfigureConnector("default", "ghost", "mail", Map.of()));
		assertTrue(noContext.getMessage().contains("ghost"));
		EventsException noConnector = assertThrows(EventsException.class,
				() -> engine.reconfigureConnector("default", "main", "nope", Map.of()));
		assertTrue(noConnector.getMessage().contains("nope"));
		EventsException fixed = assertThrows(EventsException.class,
				() -> engine.reconfigureConnector("default", "main", "frozen", Map.of()));
		assertTrue(fixed.getMessage().contains("not reconfigurable"));
		assertThrows(EventsException.class, () -> engine.reconfigureConnector("nope", Map.of()));
	}

	@Test
	@DisplayName("a refusal by the connector surfaces as a ConnectorException")
	void connectorRefusal() {
		assertThrows(ConnectorException.class,
				() -> engine.reconfigureConnector("default", "main", "mail", Map.of("bad", "1")));
	}
}
