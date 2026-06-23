package com.garganttua.events.core.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.core.dsl.DslException;
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
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.SubscriptionDef;

/**
 * Tests the manual {@code connector(IClass)} DSL path and the {@code @Connector} resolution
 * behaviour of {@link EventsBuilder}: an annotated connector class is registered under its
 * {@code type:version} key, while a class without {@code @Connector} is rejected.
 */
@DisplayName("EventsBuilder @Connector resolution")
class EventsBuilderConnectorTest {

	/** Annotated test connector — minimal IConnector stub. */
	@Connector(type = "test", version = "9.9")
	static final class TestConnector extends AbstractLifecycle implements IConnector {
		@Override
		public IReflection reflection() {
			return IClass.getReflection();
		}

		@Override
		public String getName() {
			return "test";
		}

		@Override
		public void configure(Map<String, String> configuration, ConnectorContext ctx) {
			// no-op stub
		}

		@Override
		public IConsumer createConsumer(SubscriptionDef sub, DataflowDef df) {
			return null;
		}

		@Override
		public IProducer createProducer(SubscriptionDef sub, DataflowDef df) {
			return null;
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

	/** Connector class WITHOUT @Connector — manual registration must reject it. */
	static final class UnmarkedConnector extends AbstractLifecycle implements IConnector {
		@Override
		public IReflection reflection() {
			return IClass.getReflection();
		}

		@Override
		public String getName() {
			return "unmarked";
		}

		@Override
		public void configure(Map<String, String> configuration, ConnectorContext ctx) {
			// no-op stub
		}

		@Override
		public IConsumer createConsumer(SubscriptionDef sub, DataflowDef df) {
			return null;
		}

		@Override
		public IProducer createProducer(SubscriptionDef sub, DataflowDef df) {
			return null;
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

	private EventsBuilder builder;

	@BeforeAll
	static void setUpReflection() throws Exception {
		// Install a JVM reflection provider so IClass.getClass(...) (used in EventsBuilder's
		// static DEPENDENCIES init) resolves; mirrors core's bootstrap tests.
		IClass.setReflection(ReflectionBuilder.builder()
				.withProvider(new RuntimeReflectionProvider())
				.build());
	}

	@AfterAll
	static void tearDownReflection() {
		IClass.setReflection(null);
	}

	@BeforeEach
	void setUp() {
		this.builder = (EventsBuilder) EventsBuilder.builder();
	}

	@Nested
	@DisplayName("manual connector(IClass)")
	class ManualPath {

		@Test
		@DisplayName("registers an @Connector class under its type:version key")
		void registersAnnotatedConnector() {
			IClass<? extends IConnector> clazz = IClass.getClass(TestConnector.class);

			builder.connector(clazz);

			assertEquals(1, builder.registeredConnectors().size());
			assertSame(clazz, builder.registeredConnectors().get("test:9.9"));
		}

		@Test
		@DisplayName("rejects a class without @Connector")
		void rejectsUnmarkedConnector() {
			IClass<? extends IConnector> clazz = IClass.getClass(UnmarkedConnector.class);

			assertThrows(DslException.class, () -> builder.connector(clazz));
		}
	}

	@Nested
	@DisplayName("auto-detection")
	class AutoDetection {

		@Test
		@DisplayName("doAutoDetection does not throw")
		void doAutoDetectionDoesNotThrow() {
			assertDoesNotThrow(() -> builder.doAutoDetection());
		}
	}
}
