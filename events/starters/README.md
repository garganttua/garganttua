# garganttua-events — starters

Batteries-included consumption starters for **garganttua-events**, mirroring `core/starters`.
Each starter is a `pom`-packaged dependency bundle: depend on **one** coordinate and get the
events engine, a connector, the JVM reflection stack and the bootstrap.

| Starter | Bundles |
|---|---|
| `garganttua-events-starter-bus` | events-core + `connector-bus` (in-memory BigQueue) + starter-runtime + bootstrap |
| `garganttua-events-starter-kafka` | events-core + `connector-kafka` (Apache Kafka) + starter-runtime + bootstrap |
| `garganttua-events-starter-mail` | events-core + `connector-mail` (Angus Mail) + starter-runtime + bootstrap |
| `garganttua-events-starter-observability` | events-core + `connector-observability` (observe any garganttua `IObservable`) + starter-runtime + bootstrap |
| `garganttua-events-starter-api` | events-core + `connector-api` (observe garganttua-api business events; pulls `connector-observability`) + starter-runtime + bootstrap |

Each pulls (transitively):
- **`garganttua-events-core`** — the `EventsBuilder` DSL + the `IEvents` engine.
- **one connector** — `garganttua-events-connector-{bus,kafka,mail,observability,api}`.
- **`garganttua-starter-runtime`** — the JVM reflection providers (runtime-reflection + reflections).
- **`garganttua-bootstrap`** — wires reflection + injection + expression + the auto-loaded events module.

## Usage

```xml
<dependency>
  <groupId>com.garganttua</groupId>
  <artifactId>garganttua-events-starter-bus</artifactId>
  <version>3.0.0-ALPHA05</version>
  <type>pom</type>
</dependency>
```

Then bootstrap as in the [garganttua-events example](https://github.com/garganttua/garganttua-events-example).

> AOT/native variants are not provided yet — these starters bundle the **JVM** reflection
> stack (`garganttua-starter-runtime`). For native, swap in `garganttua-starter-aot`.
