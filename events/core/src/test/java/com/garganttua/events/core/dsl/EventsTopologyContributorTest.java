package com.garganttua.events.core.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.SubscriptionDef;
import com.garganttua.events.api.dsl.IEventsBuilder;
import com.garganttua.events.api.dsl.IEventsTopologyContributor;
import com.garganttua.events.api.exceptions.EventsException;

/**
 * Proves the {@link IEventsTopologyContributor} seam: {@link EventsBuilderFactory#create()},
 * invoked at bootstrap REGISTRATION, applies every {@code ServiceLoader}-discovered contributor to
 * the shared {@link EventsBuilder} it returns — so a contributor-supplied topology rides into the
 * builder the bootstrap builds and publishes as the {@code IEvents} bean. A throwing contributor is
 * isolated and never aborts the factory.
 *
 * <p>The test-classpath descriptor
 * {@code META-INF/services/com.garganttua.events.api.dsl.IEventsTopologyContributor} lists both
 * {@link TopologyContributor} (well-behaved) and {@link ThrowingContributor} (failing).</p>
 */
@DisplayName("EventsBuilderFactory — IEventsTopologyContributor seam")
class EventsTopologyContributorTest {

    /** The connector type:version the well-behaved contributor registers. */
    static final String CONNECTOR_KEY = "test:9.9";

    @BeforeAll
    static void setUpReflection() {
        // EventsBuilder's static DEPENDENCIES init + connector(IClass) resolution need a provider.
        IClass.setReflection(ReflectionBuilder.builder()
                .withProvider(new RuntimeReflectionProvider())
                .build());
    }

    @AfterAll
    static void tearDownReflection() {
        IClass.setReflection(null);
    }

    @Nested
    @DisplayName("contribution")
    class Contribution {

        @Test
        @DisplayName("the factory applies a discovered contributor's topology to the shared builder")
        void appliesTopology() {
            EventsBuilder builder = assertDoesNotThrow(
                    () -> (EventsBuilder) new EventsBuilderFactory().create());

            assertEquals("contributed-asset", builder.assetId,
                    "the contributor's asset should ride into the shared builder");
            assertEquals(1, builder.contextCount(),
                    "the contributor's cluster context should be present");
            assertSame(IClass.getClass(TestConnector.class),
                    builder.registeredConnectors().get(CONNECTOR_KEY),
                    "the contributor's connector should be registered under its type:version");
        }
    }

    @Nested
    @DisplayName("isolation")
    class Isolation {

        @Test
        @DisplayName("a throwing contributor is skipped and the factory still returns a usable builder")
        void throwingContributorIsIsolated() {
            // Both contributors are on the descriptor; ThrowingContributor runs first (order -10).
            EventsBuilder builder = assertDoesNotThrow(
                    () -> (EventsBuilder) new EventsBuilderFactory().create(),
                    "a failing contributor must not propagate out of create()");

            assertEquals("contributed-asset", builder.assetId,
                    "the well-behaved contributor must still have applied despite the failing one");
            assertTrue(builder.registeredConnectors().containsKey(CONNECTOR_KEY),
                    "the well-behaved contributor's connector must still be registered");
        }
    }

    /** Well-behaved contributor: sets an asset, a cluster context and a connector. */
    public static final class TopologyContributor implements IEventsTopologyContributor {
        @Override
        public void contribute(IEventsBuilder events) throws EventsException {
            events.asset("contributed-asset")
                    .context("tenant-a", "cluster-1").up()
                    .connector(IClass.getClass(TestConnector.class));
        }
    }

    /** Failing contributor: always throws — must be isolated by the factory. Runs first. */
    public static final class ThrowingContributor implements IEventsTopologyContributor {
        @Override
        public int order() {
            return -10;
        }

        @Override
        public void contribute(IEventsBuilder events) throws EventsException {
            throw new EventsException("intentional contributor failure");
        }
    }

    /** Annotated test connector — minimal IConnector stub registered by {@link TopologyContributor}. */
    @Connector(type = "test", version = "9.9")
    public static final class TestConnector extends AbstractLifecycle implements IConnector {
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
}
