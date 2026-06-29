# Garganttua

<!-- AUTO-GENERATED-COVERAGE-START -->
![coverage](https://img.shields.io/badge/coverage-70.8%25%20instructions-green)

Coverage: **70.8%** instructions · **60.0%** branches · **69.8%** lines across 38 modules (JaCoCo; full per-module report in CI artifacts).
<!-- AUTO-GENERATED-COVERAGE-STOP -->

Unified reactor aggregating the three Garganttua framework libraries that form a tight,
release-synchronised dependency chain: **core → api → events**. Building them in one Maven
reactor removes the cross-repo version dance — internal dependencies resolve from the reactor,
not from GitHub Packages.

- **core** — foundation: DI, reflection, expression language, scripting, workflow engine
- **api** — declarative REST API generation with multi-tenancy and security (depends on core)
- **events** — event routing (Kafka, BigQueue, mail), routes compile to core workflows (depends on core)

Single unified version, Java 25. See [CLAUDE.md](CLAUDE.md) for build, conventions, and
per-library detail.

## Build

The same two-pass build core documents (the AOT annotation processor is otherwise a
clean-repo circular dependency):

```bash
# 1. seed the local repo (proc=none) so the annotation processor + all artifacts exist
mvn -Pbootstrap-no-apt -o install -DskipTests
# 2. normal build (annotation processing on)
mvn -o install
```

Requires JDK 25.

## Architecture

<!-- AUTO-GENERATED-ARCHITECTURE-START -->
| Module | Description |
|:--|:--|
| [**garganttua**](././README.md) |  |
| \|- [**api**](./api/README.md) | Declarative, annotation-driven REST API framework — multi-tenancy, pluggable security, AOT/native-ready — built on garganttua-core. |
| \|    \|- [**bindings**](./api/bindings/README.md) | Aggregator for third-party bindings — each submodule wraps one external library (Jackson, SLF4J, JsonPath, MongoDB driver, Javalin) so consumers depend on a binding artifact, keeping library swaps a pom-level edit. |
| \|    \|    \|- [**binding-jackson**](./api/bindings/binding-jackson/README.md) | Binding wrapping Jackson (annotations, core, databind, dataformat-xml + geojson-jackson) — pins the JSON/XML (de)serialization library used across the API and ships the framework's JSON and XML ISerializer implementations. |
| \|    \|    \|- [**binding-javalin**](./api/bindings/binding-javalin/README.md) | Binding wrapping Javalin (lightweight HTTP server). Ships a Javalin-backed IInterface (transport entry point) plus its companion IProtocol Context adapter. |
| \|    \|    \|- [**binding-jsonpath**](./api/bindings/binding-jsonpath/README.md) | Binding wrapping Jayway JsonPath — isolates the json-path dependency (used by the JWT security module for claims extraction). |
| \|    \|    \|- [**binding-mongodb**](./api/bindings/binding-mongodb/README.md) | Binding wrapping the MongoDB sync driver — consumed by garganttua-api-dao-mongodb. |
| \|    \|    \|- [**binding-slf4j**](./api/bindings/binding-slf4j/README.md) | Binding wrapping SLF4J (façade + simple impl) — opt-in classic SLF4J logging for downstream apps and bridging into the framework's observability logger. |
| \|    \|- [**commons**](./api/commons/README.md) | Pure contract layer: interfaces, annotations, enums and definition records shared by every API module. Zero business logic. |
| \|    \|- [**core**](./api/core/README.md) | Core engine: DSL builders, definition/context model, request pipeline and workflow assembly, repository filters and security expressions. |
| \|    \|- [**dao**](./api/dao/README.md) | Data-access abstractions for entity persistence (parent module). |
| \|    \|    \|- [**dao-mongodb**](./api/dao/dao-mongodb/README.md) | MongoDB DAO implementation — native-ready repository backed by the MongoDB driver. |
| \|    \|- [**interface**](./api/interface/README.md) | Interface-layer abstractions for exposing domains over transport protocols (parent module). |
| \|    \|    \|- [**interface-rest**](./api/interface/interface-rest/README.md) | REST interface binding — maps domain CRUD operations to HTTP/REST endpoints. |
| \|    \|- [**javalin**](./api/javalin/README.md) | Javalin HTTP integration — serves API domains over a lightweight Javalin web layer. |
| \|    \|- [**native-image**](./api/native-image/README.md) | GraalVM native-image support (parent module). |
| \|    \|    \|- [**native-image-config**](./api/native-image/native-image-config/README.md) | Generates native-image reflection/resource configuration for API modules. |
| \|    \|- [**security**](./api/security/README.md) | Security implementations: authentication strategies and authorization protocols (parent module). |
| \|    \|    \|- [**security-authentication-authorization**](./api/security/security-authentication-authorization/README.md) | Authorization-token authentication: authenticate a caller from an existing authorization (refresh flow). |
| \|    \|    \|- [**security-authentication-challenge**](./api/security/security-authentication-challenge/README.md) | Challenge-response authentication strategy. |
| \|    \|    \|- [**security-authentication-login-password**](./api/security/security-authentication-login-password/README.md) | Login + password (bcrypt) authentication strategy with account-status checks. |
| \|    \|    \|- [**security-authentication-pin**](./api/security/security-authentication-pin/README.md) | PIN-code authentication strategy with error-counter lockout. |
| \|    \|    \|- [**security-authorization-jwt**](./api/security/security-authorization-jwt/README.md) | JWT authorization: signable/refreshable JWT tokens (pending migration to the 3.0.0 core). |
| \|    \|- [**starters**](./api/starters/README.md) | Opinionated Spring Boot / Javalin starters bundling a ready-to-run API stack (parent module). |
| \|    \|    \|- [**starter-aot-mongo-javalin**](./api/starters/starter-aot-mongo-javalin/README.md) | AOT/native starter: same stack as jvm-mongo-javalin but with AOT reflection 		providers ahead of the runtime ones (GraalVM-ready). |
| \|    \|    \|- [**starter-bootstrap**](./api/starters/starter-bootstrap/README.md) | Bootstrap starter: a Spring-Boot-style runner (GarganttuaApplication.run) that 		assembles the framework, scans @Entity/@Dto, runs ServiceLoader auto-configs and reads 		application.yaml — transport- and persistence-agnostic (no Mongo, no Javalin). |
| \|    \|    \|- [**starter-javalin**](./api/starters/starter-javalin/README.md) | Javalin add-on starter: exposes every annotation-scanned domain over HTTP on a 		shared Javalin server (server.port), with JSON serialization out of the box. |
| \|    \|    \|- [**starter-jvm-mongo-javalin**](./api/starters/starter-jvm-mongo-javalin/README.md) | JVM starter: bootstrap runner + MongoDB persistence + Javalin HTTP, runtime reflection. |
| \|    \|    \|- [**starter-mongodb**](./api/starters/starter-mongodb/README.md) | MongoDB add-on starter: auto-wires a default MongoDB DAO from application.yaml 		(mongodb.uri / mongodb.database) onto every annotation-scanned domain. |
| \|    \|    \|- [**starter-quickstart**](./api/starters/starter-quickstart/README.md) | Quickstart starter: the bootstrap runner with the runtime reflection stack — 		no persistence, no transport. Supply your own in-memory IDao for tutorials and tests. |
| \|- [**core**](./core/README.md) | Garganttua Core - Foundational Java framework for dependency injection, workflow orchestration, reflection utilities, and more. |
| \|    \|- [**aot**](./core/aot/README.md) | Garganttua AOT (Ahead-of-Time) compilation support - parent module. |
| \|    \|    \|- [**aot-annotation-processor**](./core/aot/aot-annotation-processor/README.md) | Compile-time annotation processor for generating AOT class descriptors. |
| \|    \|    \|- [**aot-annotation-scanner**](./core/aot/aot-annotation-scanner/README.md) | Compile-time annotation scanner for AOT class descriptor generation. |
| \|    \|    \|- [**aot-commons**](./core/aot/aot-commons/README.md) | Common interfaces and types for Garganttua AOT (Ahead-of-Time) compilation support. |
| \|    \|    \|- [**aot-maven-plugin**](./core/aot/aot-maven-plugin/README.md) | Maven plugin for Garganttua AOT processing. |
| \|    \|    \|- [**aot-native-feature**](./core/aot/aot-native-feature/README.md) | GraalVM native-image Feature that registers AOT descriptors with RuntimeReflection at analysis time, removing the need for a hand-written reflect-config.json on top of the consumer-side AOT pipeline. |
| \|    \|    \|- [**aot-reflection**](./core/aot/aot-reflection/README.md) | AOT reflection descriptors and registry for Garganttua Core. |
| \|    \|- [**bindings**](./core/bindings/README.md) | Modules providing bindings to external libs and frameworks. |
| \|    \|    \|- [**mutex-redis**](./core/bindings/mutex-redis/README.md) | Distributed mutex over redis. |
| \|    \|    \|- [**native**](./core/bindings/native/README.md) | Garganttua Native support - parent module. |
| \|    \|    \|    \|- [**native-commons**](./core/bindings/native/native-commons/README.md) | Low-level native integrations and system abstractions. |
| \|    \|    \|    \|- [**native-image-maven-plugin**](./core/bindings/native/native-image-maven-plugin/README.md) | Maven plugin to build native images (GraalVM support). |
| \|    \|    \|- [**reflections**](./core/bindings/reflections/README.md) | Annotation scanner implementation based on org.reflections:reflections |
| \|    \|    \|- [**spring**](./core/bindings/spring/README.md) | Spring framework integration for Garganttua Core modules. |
| \|    \|- [**bootstrap**](./core/bootstrap/README.md) | Bootstrap and application initialization framework. |
| \|    \|- [**classloader**](./core/classloader/README.md) | Bootstrap-discoverable JAR hot-loader that adds JARs to the thread context classloader and notifies registered rebuild hooks. Decouples runtime JAR loading from the Bootstrap module itself. |
| \|    \|- [**commons**](./core/commons/README.md) | Shared components, interfaces, annotations, and exceptions. |
| \|    \|- [**condition**](./core/condition/README.md) | DSL to define, combine, and evaluate runtime conditions. |
| \|    \|- [**configuration**](./core/configuration/README.md) | Multi-format configuration loading and builder population for Garganttua DSLs. |
| \|    \|- [**console**](./core/console/README.md) | Interactive REPL console for Garganttua Script. |
| \|    \|- [**crypto**](./core/crypto/README.md) | Encryption, hashing, and secure key management utilities. |
| \|    \|- [**dsl**](./core/dsl/README.md) | Declarative language and builder framework for Garganttua DSLs. |
| \|    \|- [**examples**](./core/examples/README.md) | Aggregator for runnable consumer-side examples; kept out of the root reactor so a plain root `mvn install` never depends on them — build with `mvn -f garganttua-examples/pom.xml install`. |
| \|    \|    \|- [**example-aot-only**](./core/examples/example-aot-only/README.md) | Minimal "AOT-only" consumer proving Garganttua AOT reflection (compile-time rich IClass descriptors) runs on just the AOT starter + reflection facade, with no injection, runtime, expression, bootstrap or workflow. |
| \|    \|- [**execution**](./core/execution/README.md) | Task execution, orchestration, and fallback handling engine. |
| \|    \|- [**expression**](./core/expression/README.md) | Advanced expression language for object supplying. |
| \|    \|- [**injection**](./core/injection/README.md) | Dependency injection container with modular context support. |
| \|    \|- [**lifecycle**](./core/lifecycle/README.md) | Abstract lifecycle management with thread-safe state transitions. |
| \|    \|- [**mapper**](./core/mapper/README.md) | Declarative object-to-object mapping engine. |
| \|    \|- [**mutex**](./core/mutex/README.md) | Thread-safe mutex synchronization with configurable acquisition strategies. |
| \|    \|- [**observability**](./core/observability/README.md) | Generic observer pattern primitives with sealed event hierarchy and script-side instrumentation. |
| \|    \|- [**properties**](./core/properties/README.md) | Property provider that loads application.properties files from classpath and filesystem into the injection context. |
| \|    \|- [**reflection**](./core/reflection/README.md) | Advanced reflection utilities for classes, methods, and annotations. |
| \|    \|- [**runtime**](./core/runtime/README.md) | Runtime context management and lifecycle orchestration. |
| \|    \|- [**runtime-reflection**](./core/runtime-reflection/README.md) | Runtime reflection utilities for Garganttua Core. |
| \|    \|- [**script**](./core/script/README.md) | Scripting language engine with variables, control flow, and expression evaluation. |
| \|    \|- [**script-maven-plugin**](./core/script-maven-plugin/README.md) | Maven plugin to build JARs that can be included in Garganttua scripts (.gs files). Automatically adds Garganttua-Packages manifest attribute. |
| \|    \|- [**starters**](./core/starters/README.md) | Aggregator for the four consumption starters (aot / runtime / hybrid / native) that bundle the reflection providers + scanners a downstream application needs to pick a reflection mode by changing a single Maven coordinate. |
| \|    \|    \|- [**starter-aot**](./core/starters/starter-aot/README.md) | Pure-AOT starter: pulls the AOT reflection provider + annotation scanner. Cold-start optimised, no runtime classpath scan, prep for native-image. |
| \|    \|    \|- [**starter-application**](./core/starters/starter-application/README.md) | Neutral core-level application runner: GarganttuaApplication.run(source) boots garganttua-core's Bootstrap (autoDetect + SPI discovery of every IBootstrapBuilderFactory on the classpath) and returns the queryable IBuiltRegistry of bootstrapped modules. No api/events dependency. |
| \|    \|    \|- [**starter-hybrid**](./core/starters/starter-hybrid/README.md) | Hybrid starter (recommended default for dev): AOT prioritised at @Priority(20), runtime/reflections fallback at @Priority(10) for types the AOT processor didn't see. Belt and suspenders. |
| \|    \|    \|- [**starter-native**](./core/starters/starter-native/README.md) | GraalVM native-image starter: pure AOT (same as garganttua-starter-aot) plus garganttua-aot-native-feature that registers every AOT descriptor with RuntimeReflection at native-image analysis time. Consumer still wires the native-maven-plugin in their pom (see this module README). |
| \|    \|    \|- [**starter-runtime**](./core/starters/starter-runtime/README.md) | Runtime / legacy starter: JDK reflection + org.reflections-based classpath scanner. Quickest dev / debug loop, no AOT processor required. |
| \|    \|- [**supply**](./core/supply/README.md) | Object suppliers and contextual provisioning utilities. |
| \|    \|- [**workflow**](./core/workflow/README.md) | Workflow orchestration module - DSL builder for chaining scripts with dynamic script generation |
| \|- [**events**](./events/README.md) |  |
| \|    \|- [**api**](./events/api/README.md) |  |
| \|    \|- [**connector-api**](./events/connector-api/README.md) |  |
| \|    \|- [**connector-bus**](./events/connector-bus/README.md) |  |
| \|    \|- [**connector-kafka**](./events/connector-kafka/README.md) |  |
| \|    \|- [**connector-mail**](./events/connector-mail/README.md) |  |
| \|    \|- [**connector-observability**](./events/connector-observability/README.md) |  |
| \|    \|- [**connector-websocket**](./events/connector-websocket/README.md) |  |
| \|    \|- [**core**](./events/core/README.md) |  |
| \|    \|- [**expressions**](./events/expressions/README.md) |  |
| \|    \|- [**starters**](./events/starters/README.md) | Aggregator for the garganttua-events consumption starters. Each starter         bundles the events engine (DSL + core), one connector (bus / kafka / mail), the JVM         reflection stack and the bootstrap — so a downstream app picks a transport by         depending on a single Maven coordinate. |
| \|    \|    \|- [**starter-api**](./events/starters/starter-api/README.md) | Batteries-included garganttua-events starter for the garganttua-api connector:         events engine (DSL + core) + api connector (observe garganttua-api business events) +         observability connector (full firehose) + JVM reflection stack + bootstrap. Both         connectors self-register on the global observability firehose, so an api-events app         receives events with zero wiring. Depend on this single artifact to build/run an events         app that ingests api business events. |
| \|    \|    \|- [**starter-bus**](./events/starters/starter-bus/README.md) | Batteries-included garganttua-events starter for the in-memory BigQueue bus         connector: events engine (DSL + core) + bus connector + JVM reflection stack +         bootstrap. Depend on this single artifact to build/run an events app on the bus. |
| \|    \|    \|- [**starter-kafka**](./events/starters/starter-kafka/README.md) | Batteries-included garganttua-events starter for the Apache Kafka connector:         events engine (DSL + core) + Kafka connector + JVM reflection stack + bootstrap.         Depend on this single artifact to build/run an events app on Kafka. |
| \|    \|    \|- [**starter-mail**](./events/starters/starter-mail/README.md) | Batteries-included garganttua-events starter for the e-mail (Angus Mail)         connector: events engine (DSL + core) + mail connector + JVM reflection stack +         bootstrap. Depend on this single artifact to build/run an events app producing mail. |
| \|    \|    \|- [**starter-observability**](./events/starters/starter-observability/README.md) | Batteries-included garganttua-events starter for the observability connector:         events engine (DSL + core) + observability connector (observe any garganttua IObservable —         workflow, runtime, mapper, bootstrap...) + JVM reflection stack + bootstrap. Depend on this         single artifact to build/run an events app that ingests garganttua observability events. |
| \|    \|    \|- [**starter-websocket**](./events/starters/starter-websocket/README.md) | Batteries-included garganttua-events starter for the bidirectional WebSocket         connector (consumer + producer, client/server topology, pub/sub channels): events engine         (DSL + core) + websocket connector + JVM reflection stack + bootstrap. Depend on this         single artifact to build/run an events app over WebSocket. |
<!-- AUTO-GENERATED-ARCHITECTURE-STOP -->

## Internal dependency graph

<!-- AUTO-GENERATED-DEPENDENCIES-GRAPH-START -->
```mermaid
graph TD

```
<!-- AUTO-GENERATED-DEPENDENCIES-GRAPH-STOP -->

## Installation

<!-- AUTO-GENERATED-START -->
### Installation with Maven
```xml
<dependency>
    <groupId>com.garganttua</groupId>
    <artifactId>garganttua</artifactId>
    <version>3.0.0-ALPHA04</version>
</dependency>
```

### Actual version
3.0.0-ALPHA04

### Dependencies

<!-- AUTO-GENERATED-END -->

## License

This project is distributed under the MIT License. See [LICENSE](LICENSE).
