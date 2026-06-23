# garganttua-events-example-bus

Minimal runnable garganttua-events demo, driven by the **Bootstrap**.

## What it does

1. `Bootstrap.builder()` runs garganttua-core's ServiceLoader cold-start, installing the
   reflection facade the DSL needs.
2. Defines an events topology with the `EventsBuilder` DSL (one asset, one tenant/cluster
   context, a topic, and a route of expression stages).
3. Registers that configured builder, then `autoDetect(true) + load()` lets the bootstrap
   discover and wire the reflection / injection / expression layers and build everything.
4. Pulls the assembled `IEvents` engine out of the built registry (also published as the
   injection bean `events`) and prints its asset id.

```java
IBootstrap bootstrap = Bootstrap.builder();              // cold-start installs reflection

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

bootstrap.autoDetect(true).withBuilder((IBuilder<?>) events).load();
IBuiltRegistry registry = bootstrap.build();

IEvents engine = registry.toList().stream()
        .filter(IEvents.class::isInstance).map(IEvents.class::cast)
        .findFirst().orElse(null);
```

## Run

```bash
export JAVA_HOME=<jdk-25>
mvn -f events/examples/pom.xml -pl example-bus exec:java
```

Expected output:

```
=== Garganttua Events example (bootstrap) ===
Topology defined via EventsBuilder DSL (asset 'orders-asset').
Bootstrap built (reflection + injection + expression + events).
IEvents engine assembled — asset = orders-asset
```

## Status

This example demonstrates the **DSL**, the **bootstrap auto-wiring**, and the assembled
**`IEvents`** engine. End-to-end *live message flow* through a connector (e.g. starting the
engine so the in-memory bus connector consumes/produces) is **work-in-progress** — connector
resolution at runtime is still being designed. The `garganttua-events-connector-bus`
dependency is on the classpath so the `bus` connector type is available for that next step.
