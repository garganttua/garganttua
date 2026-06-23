# garganttua-events — examples

Runnable, consumer-side examples for **garganttua-events**, mirroring `core/examples`.

Kept **out of the root reactor** so a plain root build never depends on them. Each example
depends on the published `garganttua` artifacts exactly like a downstream application would.

## Build / run

Requires **JDK 25**.

```bash
# build all examples
mvn -f events/examples/pom.xml install

# run one (see its README)
mvn -f events/examples/pom.xml -pl example-bus exec:java
```

## Examples

| Module | What it shows |
|---|---|
| [`example-bus`](example-bus/README.md) | Bootstrap auto-wires the events stack; an events topology is defined with the `EventsBuilder` DSL and the assembled `IEvents` engine is retrieved. |
