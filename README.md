# Garganttua

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
| \|    \|- [**garganttua-api-bindings**](./api/garganttua-api-bindings/README.md) | Aggregator for third-party bindings — each submodule wraps one external library (Jackson, SLF4J, JsonPath, MongoDB driver, Javalin) so consumers depend on a binding artifact, keeping library swaps a pom-level edit. |
| \|    \|    \|- [**garganttua-api-binding-jackson**](./api/garganttua-api-bindings/garganttua-api-binding-jackson/README.md) | Binding wrapping Jackson (annotations, core, databind, dataformat-xml + geojson-jackson) — pins the JSON/XML (de)serialization library used across the API and ships the framework's JSON and XML ISerializer implementations. |
| \|    \|    \|- [**garganttua-api-binding-javalin**](./api/garganttua-api-bindings/garganttua-api-binding-javalin/README.md) | Binding wrapping Javalin (lightweight HTTP server). Ships a Javalin-backed IInterface (transport entry point) plus its companion IProtocol Context adapter. |
| \|    \|    \|- [**garganttua-api-binding-jsonpath**](./api/garganttua-api-bindings/garganttua-api-binding-jsonpath/README.md) | Binding wrapping Jayway JsonPath — isolates the json-path dependency (used by the JWT security module for claims extraction). |
| \|    \|    \|- [**garganttua-api-binding-mongodb**](./api/garganttua-api-bindings/garganttua-api-binding-mongodb/README.md) | Binding wrapping the MongoDB sync driver — consumed by garganttua-api-dao-mongodb. |
| \|    \|    \|- [**garganttua-api-binding-slf4j**](./api/garganttua-api-bindings/garganttua-api-binding-slf4j/README.md) | Binding wrapping SLF4J (façade + simple impl) — opt-in classic SLF4J logging for downstream apps and bridging into the framework's observability logger. |
| \|    \|- [**garganttua-api-commons**](./api/garganttua-api-commons/README.md) | Pure contract layer: interfaces, annotations, enums and definition records shared by every API module. Zero business logic. |
| \|    \|- [**garganttua-api-core**](./api/garganttua-api-core/README.md) | Core engine: DSL builders, definition/context model, request pipeline and workflow assembly, repository filters and security expressions. |
| \|    \|- [**garganttua-api-dao**](./api/garganttua-api-dao/README.md) | Data-access abstractions for entity persistence (parent module). |
| \|    \|    \|- [**garganttua-api-dao-mongodb**](./api/garganttua-api-dao/garganttua-api-dao-mongodb/README.md) | MongoDB DAO implementation — native-ready repository backed by the MongoDB driver. |
| \|    \|- [**garganttua-api-interface**](./api/garganttua-api-interface/README.md) | Interface-layer abstractions for exposing domains over transport protocols (parent module). |
| \|    \|    \|- [**garganttua-api-interface-rest**](./api/garganttua-api-interface/garganttua-api-interface-rest/README.md) | REST interface binding — maps domain CRUD operations to HTTP/REST endpoints. |
| \|    \|- [**garganttua-api-javalin**](./api/garganttua-api-javalin/README.md) | Javalin HTTP integration — serves API domains over a lightweight Javalin web layer. |
| \|    \|- [**garganttua-api-native-image**](./api/garganttua-api-native-image/README.md) | GraalVM native-image support (parent module). |
| \|    \|    \|- [**garganttua-api-native-image-config**](./api/garganttua-api-native-image/garganttua-api-native-image-config/README.md) | Generates native-image reflection/resource configuration for API modules. |
| \|    \|- [**garganttua-api-security**](./api/garganttua-api-security/README.md) | Security implementations: authentication strategies and authorization protocols (parent module). |
| \|    \|    \|- [**garganttua-api-security-authentication-authorization**](./api/garganttua-api-security/garganttua-api-security-authentication-authorization/README.md) | Authorization-token authentication: authenticate a caller from an existing authorization (refresh flow). |
| \|    \|    \|- [**garganttua-api-security-authentication-challenge**](./api/garganttua-api-security/garganttua-api-security-authentication-challenge/README.md) | Challenge-response authentication strategy. |
| \|    \|    \|- [**garganttua-api-security-authentication-login-password**](./api/garganttua-api-security/garganttua-api-security-authentication-login-password/README.md) | Login + password (bcrypt) authentication strategy with account-status checks. |
| \|    \|    \|- [**garganttua-api-security-authentication-pin**](./api/garganttua-api-security/garganttua-api-security-authentication-pin/README.md) | PIN-code authentication strategy with error-counter lockout. |
| \|    \|    \|- [**garganttua-api-security-authorization-jwt**](./api/garganttua-api-security/garganttua-api-security-authorization-jwt/README.md) | JWT authorization: signable/refreshable JWT tokens (pending migration to the 3.0.0 core). |
| \|    \|- [**garganttua-api-starters**](./api/garganttua-api-starters/README.md) | Opinionated Spring Boot / Javalin starters bundling a ready-to-run API stack (parent module). |
| \|    \|    \|- [**garganttua-api-starter-aot-mongo-javalin**](./api/garganttua-api-starters/garganttua-api-starter-aot-mongo-javalin/README.md) | AOT/native starter: same stack as jvm-mongo-javalin but with AOT reflection 		providers ahead of the runtime ones (GraalVM-ready). |
| \|    \|    \|- [**garganttua-api-starter-bootstrap**](./api/garganttua-api-starters/garganttua-api-starter-bootstrap/README.md) | Bootstrap starter: a Spring-Boot-style runner (GarganttuaApplication.run) that 		assembles the framework, scans @Entity/@Dto, runs ServiceLoader auto-configs and reads 		application.yaml — transport- and persistence-agnostic (no Mongo, no Javalin). |
| \|    \|    \|- [**garganttua-api-starter-javalin**](./api/garganttua-api-starters/garganttua-api-starter-javalin/README.md) | Javalin add-on starter: exposes every annotation-scanned domain over HTTP on a 		shared Javalin server (server.port), with JSON serialization out of the box. |
| \|    \|    \|- [**garganttua-api-starter-jvm-mongo-javalin**](./api/garganttua-api-starters/garganttua-api-starter-jvm-mongo-javalin/README.md) | JVM starter: bootstrap runner + MongoDB persistence + Javalin HTTP, runtime reflection. |
| \|    \|    \|- [**garganttua-api-starter-mongodb**](./api/garganttua-api-starters/garganttua-api-starter-mongodb/README.md) | MongoDB add-on starter: auto-wires a default MongoDB DAO from application.yaml 		(mongodb.uri / mongodb.database) onto every annotation-scanned domain. |
| \|    \|    \|- [**garganttua-api-starter-quickstart**](./api/garganttua-api-starters/garganttua-api-starter-quickstart/README.md) | Quickstart starter: the bootstrap runner with the runtime reflection stack — 		no persistence, no transport. Supply your own in-memory IDao for tutorials and tests. |
| \|- [**core**](./core/README.md) | Garganttua Core - Foundational Java framework for dependency injection, workflow orchestration, reflection utilities, and more. |
| \|    \|- [**garganttua-aot**](./core/garganttua-aot/README.md) | Garganttua AOT (Ahead-of-Time) compilation support - parent module. |
| \|    \|    \|- [**garganttua-aot-annotation-processor**](./core/garganttua-aot/garganttua-aot-annotation-processor/README.md) | Compile-time annotation processor for generating AOT class descriptors. |
| \|    \|    \|- [**garganttua-aot-annotation-scanner**](./core/garganttua-aot/garganttua-aot-annotation-scanner/README.md) | Compile-time annotation scanner for AOT class descriptor generation. |
| \|    \|    \|- [**garganttua-aot-commons**](./core/garganttua-aot/garganttua-aot-commons/README.md) | Common interfaces and types for Garganttua AOT (Ahead-of-Time) compilation support. |
| \|    \|    \|- [**garganttua-aot-maven-plugin**](./core/garganttua-aot/garganttua-aot-maven-plugin/README.md) | Maven plugin for Garganttua AOT processing. |
| \|    \|    \|- [**garganttua-aot-native-feature**](./core/garganttua-aot/garganttua-aot-native-feature/README.md) | GraalVM native-image Feature that registers AOT descriptors with RuntimeReflection at analysis time, removing the need for a hand-written reflect-config.json on top of the consumer-side AOT pipeline. |
| \|    \|    \|- [**garganttua-aot-reflection**](./core/garganttua-aot/garganttua-aot-reflection/README.md) | AOT reflection descriptors and registry for Garganttua Core. |
| \|    \|- [**garganttua-bindings**](./core/garganttua-bindings/README.md) | Modules providing bindings to external libs and frameworks. |
| \|    \|    \|- [**garganttua-mutex-redis**](./core/garganttua-bindings/garganttua-mutex-redis/README.md) | Distributed mutex over redis. |
| \|    \|    \|- [**garganttua-native**](./core/garganttua-bindings/garganttua-native/README.md) | Garganttua Native support - parent module. |
| \|    \|    \|    \|- [**garganttua-native-commons**](./core/garganttua-bindings/garganttua-native/garganttua-native-commons/README.md) | Low-level native integrations and system abstractions. |
| \|    \|    \|    \|- [**garganttua-native-image-maven-plugin**](./core/garganttua-bindings/garganttua-native/garganttua-native-image-maven-plugin/README.md) | Maven plugin to build native images (GraalVM support). |
| \|    \|    \|- [**garganttua-reflections**](./core/garganttua-bindings/garganttua-reflections/README.md) | Annotation scanner implementation based on org.reflections:reflections |
| \|    \|    \|- [**garganttua-spring**](./core/garganttua-bindings/garganttua-spring/README.md) | Spring framework integration for Garganttua Core modules. |
| \|    \|- [**garganttua-bootstrap**](./core/garganttua-bootstrap/README.md) | Bootstrap and application initialization framework. |
| \|    \|- [**garganttua-classloader**](./core/garganttua-classloader/README.md) | Bootstrap-discoverable JAR hot-loader that adds JARs to the thread context classloader and notifies registered rebuild hooks. Decouples runtime JAR loading from the Bootstrap module itself. |
| \|    \|- [**garganttua-commons**](./core/garganttua-commons/README.md) | Shared components, interfaces, annotations, and exceptions. |
| \|    \|- [**garganttua-condition**](./core/garganttua-condition/README.md) | DSL to define, combine, and evaluate runtime conditions. |
| \|    \|- [**garganttua-configuration**](./core/garganttua-configuration/README.md) | Multi-format configuration loading and builder population for Garganttua DSLs. |
| \|    \|- [**garganttua-console**](./core/garganttua-console/README.md) | Interactive REPL console for Garganttua Script. |
| \|    \|- [**garganttua-crypto**](./core/garganttua-crypto/README.md) | Encryption, hashing, and secure key management utilities. |
| \|    \|- [**garganttua-dsl**](./core/garganttua-dsl/README.md) | Declarative language and builder framework for Garganttua DSLs. |
| \|    \|- [**garganttua-examples**](./core/garganttua-examples/README.md) | Aggregator for runnable consumer-side examples; kept out of the root reactor so a plain root `mvn install` never depends on them — build with `mvn -f garganttua-examples/pom.xml install`. |
| \|    \|    \|- [**garganttua-example-aot-only**](./core/garganttua-examples/garganttua-example-aot-only/README.md) | Minimal "AOT-only" consumer proving Garganttua AOT reflection (compile-time rich IClass descriptors) runs on just the AOT starter + reflection facade, with no injection, runtime, expression, bootstrap or workflow. |
| \|    \|- [**garganttua-execution**](./core/garganttua-execution/README.md) | Task execution, orchestration, and fallback handling engine. |
| \|    \|- [**garganttua-expression**](./core/garganttua-expression/README.md) | Advanced expression language for object supplying. |
| \|    \|- [**garganttua-injection**](./core/garganttua-injection/README.md) | Dependency injection container with modular context support. |
| \|    \|- [**garganttua-lifecycle**](./core/garganttua-lifecycle/README.md) | Abstract lifecycle management with thread-safe state transitions. |
| \|    \|- [**garganttua-mapper**](./core/garganttua-mapper/README.md) | Declarative object-to-object mapping engine. |
| \|    \|- [**garganttua-mutex**](./core/garganttua-mutex/README.md) | Thread-safe mutex synchronization with configurable acquisition strategies. |
| \|    \|- [**garganttua-observability**](./core/garganttua-observability/README.md) | Generic observer pattern primitives with sealed event hierarchy and script-side instrumentation. |
| \|    \|- [**garganttua-properties**](./core/garganttua-properties/README.md) | Property provider that loads application.properties files from classpath and filesystem into the injection context. |
| \|    \|- [**garganttua-reflection**](./core/garganttua-reflection/README.md) | Advanced reflection utilities for classes, methods, and annotations. |
| \|    \|- [**garganttua-runtime**](./core/garganttua-runtime/README.md) | Runtime context management and lifecycle orchestration. |
| \|    \|- [**garganttua-runtime-reflection**](./core/garganttua-runtime-reflection/README.md) | Runtime reflection utilities for Garganttua Core. |
| \|    \|- [**garganttua-script**](./core/garganttua-script/README.md) | Scripting language engine with variables, control flow, and expression evaluation. |
| \|    \|- [**garganttua-script-maven-plugin**](./core/garganttua-script-maven-plugin/README.md) | Maven plugin to build JARs that can be included in Garganttua scripts (.gs files). Automatically adds Garganttua-Packages manifest attribute. |
| \|    \|- [**garganttua-starters**](./core/garganttua-starters/README.md) | Aggregator for the four consumption starters (aot / runtime / hybrid / native) that bundle the reflection providers + scanners a downstream application needs to pick a reflection mode by changing a single Maven coordinate. |
| \|    \|    \|- [**garganttua-starter-aot**](./core/garganttua-starters/garganttua-starter-aot/README.md) | Pure-AOT starter: pulls the AOT reflection provider + annotation scanner. Cold-start optimised, no runtime classpath scan, prep for native-image. |
| \|    \|    \|- [**garganttua-starter-hybrid**](./core/garganttua-starters/garganttua-starter-hybrid/README.md) | Hybrid starter (recommended default for dev): AOT prioritised at @Priority(20), runtime/reflections fallback at @Priority(10) for types the AOT processor didn't see. Belt and suspenders. |
| \|    \|    \|- [**garganttua-starter-native**](./core/garganttua-starters/garganttua-starter-native/README.md) | GraalVM native-image starter: pure AOT (same as garganttua-starter-aot) plus garganttua-aot-native-feature that registers every AOT descriptor with RuntimeReflection at native-image analysis time. Consumer still wires the native-maven-plugin in their pom (see this module README). |
| \|    \|    \|- [**garganttua-starter-runtime**](./core/garganttua-starters/garganttua-starter-runtime/README.md) | Runtime / legacy starter: JDK reflection + org.reflections-based classpath scanner. Quickest dev / debug loop, no AOT processor required. |
| \|    \|- [**garganttua-supply**](./core/garganttua-supply/README.md) | Object suppliers and contextual provisioning utilities. |
| \|    \|- [**garganttua-workflow**](./core/garganttua-workflow/README.md) | Workflow orchestration module - DSL builder for chaining scripts with dynamic script generation |
| \|- [**events**](./events/README.md) |  |
| \|    \|- [**garganttua-events-api**](./events/garganttua-events-api/README.md) |  |
| \|    \|- [**garganttua-events-connector-bus**](./events/garganttua-events-connector-bus/README.md) |  |
| \|    \|- [**garganttua-events-connector-kafka**](./events/garganttua-events-connector-kafka/README.md) |  |
| \|    \|- [**garganttua-events-connector-mail**](./events/garganttua-events-connector-mail/README.md) |  |
| \|    \|- [**garganttua-events-core**](./events/garganttua-events-core/README.md) |  |
| \|    \|- [**garganttua-events-expressions**](./events/garganttua-events-expressions/README.md) |  |
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
