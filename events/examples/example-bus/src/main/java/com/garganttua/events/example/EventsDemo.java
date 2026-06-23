package com.garganttua.events.example;

import com.garganttua.core.bootstrap.dsl.Bootstrap;
import com.garganttua.core.bootstrap.dsl.IBootstrap;
import com.garganttua.core.bootstrap.dsl.IBuiltRegistry;
import com.garganttua.core.dsl.IBuilder;
import com.garganttua.events.api.IEvents;
import com.garganttua.events.api.dsl.IEventsBuilder;
import com.garganttua.events.core.dsl.EventsBuilder;

/**
 * Minimal runnable garganttua-events example, driven by the {@code Bootstrap}.
 *
 * <p>{@code Bootstrap.builder()} runs garganttua-core's ServiceLoader cold-start, which
 * installs the reflection facade — so the {@code EventsBuilder} DSL below (whose static
 * dependency descriptors resolve types via reflection) can be built. We then register our
 * configured events topology, let the bootstrap auto-discover and wire the
 * injection / expression layers ({@code autoDetect(true) + load()}), build everything,
 * and pull the assembled {@link IEvents} engine out of the built registry.
 *
 * <p>Run: {@code mvn -f events/examples/pom.xml -pl example-bus exec:java} (JDK 25).
 */
public final class EventsDemo {

    private EventsDemo() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Garganttua Events example (bootstrap) ===\n");

        // 1) Bootstrap first: its cold-start installs the reflection facade the DSL needs.
        IBootstrap bootstrap = Bootstrap.builder();

        // 2) Describe an events topology with the fluent DSL.
        IEventsBuilder events = EventsBuilder.builder()
                .asset("orders-asset")
                .context("tenantA", "cluster-1")
                    .topic("/orders")
                    .route("route-1", "orders-in")
                        .stage("decode").expression("protocol_in(@exchange)").up()
                        .stage("log").expression("log(@exchange)").up()
                        .to("orders-out")
                    .up()
                .up();
        System.out.println("Topology defined via EventsBuilder DSL (asset 'orders-asset').");

        // 3) Register our configured builder BEFORE load() so the SPI doesn't create a
        //    second (idle) one; autoDetect+load wire reflection/injection/expression.
        bootstrap.autoDetect(true)
                .withBuilder((IBuilder<?>) events)
                .load();
        IBuiltRegistry registry = bootstrap.build();
        System.out.println("Bootstrap built (reflection + injection + expression + events).");

        // 4) The registry is keyed by concrete class, so pick the IEvents out by type.
        IEvents engine = registry.toList().stream()
                .filter(IEvents.class::isInstance)
                .map(IEvents.class::cast)
                .findFirst()
                .orElse(null);

        if (engine != null) {
            System.out.println("\nIEvents engine assembled — asset = " + engine.getAssetId());
        } else {
            System.out.println("\nNo IEvents engine produced by the bootstrap.");
        }

        System.out.println("\nNote: end-to-end route execution through connectors is still "
                + "work-in-progress (connector resolution pending). This example demonstrates the "
                + "DSL, the bootstrap auto-wiring, and the assembled IEvents engine — not live flow yet.");
    }
}
