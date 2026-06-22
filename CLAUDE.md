# Garganttua Platform — CLAUDE.md

This file is the **single, self-contained** guidance for Claude Code at the monorepo root. It
aggregates what used to live in three separate `CLAUDE.md` files (core, api, events). When in
doubt, **garganttua-core's conventions govern the whole platform** — it is the most mature library
and the reference for every cross-cutting decision.

> **Status:** monorepo. `core` + `api` + `events` build together as one reactor (unified version
> `3.0.0-ALPHA04`, **Java 25**). `events` was migrated off the legacy `garganttua-tooling`
> architecture onto core (de-Lombok'd, observable `Logger`, `IReflectionUser`/`IClass` contracts).

## What this is

A single repository aggregating the three Garganttua framework libraries that form a tight,
release-synchronised dependency chain (`core → api → events`). Building them in one Maven reactor
removes the cross-repo version/fiche dance: internal dependencies resolve **from the reactor**, not
from GitHub Packages.

```
garganttua/
├── pom.xml          # aggregator reactor (packaging=pom, modules: core, api, events)
├── CLAUDE.md        # this file — platform-wide guidance
├── README.md        # platform overview (architecture/deps/installation auto-generated)
├── cliff.toml       # git-cliff changelog config (platform-wide)
├── LICENSE          # MIT (platform-wide)
├── new-{major,minor,patch}.sh   # version bump (mvn versions:set on the reactor)
├── scripts/         # README auto-block generators (run once at the reactor root)
├── templates/       # README.md.template used by the generators
├── config/checkstyle/           # checkstyle config consumed by the -Pquality profile
├── .github/workflows/           # CI: maven-publish, release notes, script installer
├── .claude/rules/   # platform-wide rules (promoted from garganttua-core)
├── core/            # garganttua-core   — foundation: DI, reflection, expression, scripting, workflow
├── api/             # garganttua-api    — declarative REST API generation (depends on core)
└── events/          # garganttua-events — event routing (depends on core)
```

**Shared build/config lives ONLY at the root** — there are no longer per-library copies of
`scripts/`, `cliff.toml`, `config/`, `templates/`, `LICENSE`, `.gitignore`, `new-*.sh`, `.github/`
or `CLAUDE.md`. Each library keeps only its own source tree and `pom.xml`.

## Build

The two-pass build (the AOT annotation processor is otherwise a clean-repo circular dependency):

```bash
# 1. seed the local repo (proc=none) so the annotation processor + all artifacts exist
mvn -Pbootstrap-no-apt -o install -DskipTests
# 2. normal build (annotation processing on)
mvn -o install
```

Requires **JDK 25** (`java -version` → 25.x). A clean one-pass build fails because every module
declares the in-reactor AOT annotation processor on its `annotationProcessorPath`.

Common per-module commands:

```bash
mvn -o install -pl core/garganttua-injection          # build one module (path-based)
mvn -o install -pl :garganttua-injection              # build one module (artifactId-based, path-independent)
mvn -o test                                            # all tests
mvn -o test -pl :garganttua-expression                 # tests for one module
mvn -o test -pl :garganttua-api-core -Dtest=ApiBuilderTest#method   # one method
mvn clean test jacoco:report                           # coverage (per-module target/site/jacoco)
mvn -Pquality verify                                   # advisory Checkstyle + SpotBugs + PMD (never fails the build)
./new-major.sh   # or new-minor.sh / new-patch.sh — bumps the reactor version (suffix preserved)
python3 scripts/run_all.py                             # regenerate README auto-blocks across all modules
```

> `-pl :artifactId` (the colon selector) resolves a module by artifactId regardless of its path in
> the monorepo — prefer it over the legacy bare `-pl garganttua-script` (which assumed a flat repo).

### Doc generation

`scripts/run_all.py` regenerates the `AUTO-GENERATED-*` README blocks (architecture tree, internal
dependency graph, installation). It is wired into the **reactor root** pom (`exec-maven-plugin`,
`generate-resources`, `inherited=false` → runs once) and **recurses through every module** of core,
api and events. The generators read `templates/README.md.template`; do **not** hand-edit generated
blocks.

### Build caveats

- When modifying `garganttua-script` and testing from `garganttua-workflow`, run
  `mvn install -pl :garganttua-script -DskipTests` first so the workflow module picks up the JAR.
- The shade plugin in `garganttua-script` / `garganttua-console` uses `AppendingTransformer` to
  merge annotation index files across JARs — update it when adding new `@Indexed` annotations.
- **Shade plugins producing executable JARs MUST include `ServicesResourceTransformer`** so
  `META-INF/services/*` descriptors merge — critical for the SPI cold start and native-image.

## Conventions — garganttua-core is the reference

Per-topic rules live in [`.claude/rules/`](.claude/rules/) and are auto-loaded by file-path pattern
across every library:

- [`module-architecture.md`](.claude/rules/module-architecture.md) — layer hierarchy, acyclic deps, bootstrap SPI
- [`java-conventions.md`](.claude/rules/java-conventions.md) — naming/style
- [`code-quality.md`](.claude/rules/code-quality.md) — `-Pquality` gates, god-class/long-method rules, coverage floor, logging/javadoc/README
- [`design-patterns.md`](.claude/rules/design-patterns.md) — hierarchical builders, suppliers, binders
- [`dependency-injection.md`](.claude/rules/dependency-injection.md) — bean reference format, DI interfaces
- [`runtime-workflow.md`](.claude/rules/runtime-workflow.md) — workflow engine annotations
- [`testing.md`](.claude/rules/testing.md) — JUnit 5 conventions, module test commands
- [`antlr-grammar.md`](.claude/rules/antlr-grammar.md) — ANTLR4 grammar rules (core only)

Platform-wide essentials (these win over any stale per-library note):

- **Java 25**, Maven multi-module.
- **No Lombok, no SLF4J.** The reactor is de-Lomboked; log via the observable `Logger`
  (`com.garganttua.core.observability.Logger`) — `Logger.getLogger(SomeClass.class)`, level set via
  the `garganttua.log.level` system property. Parameterize with `{}`, never string-concatenate.
  (Lombok lingers only in *inactive* api modules — `garganttua-api-security/*`,
  `garganttua-api-native-image/*` — pending migration. Do not introduce it into active code.)
- Interfaces prefixed with `I`; abstract classes prefixed with `Abstract`; tests suffixed `Test`.
- Java **records** for immutable value objects; `Optional<T>` for nullable values — never return null.
- Thread-safe by default (`Collections.synchronizedMap/List` or concurrent collections).
- Fluent DSL builders with `up()` navigation; `var` when the type is obvious; no wildcard/raw types.
- **`Class<?>` is prohibited** — use `IClass<?>` from `garganttua-commons` (`IClass.getClass(clazz)`
  to wrap). Exception: pure type-hierarchy checks may use the `IClass.isAssignableFrom(Class<?>)` /
  `IClass.represents(Class<?>)` raw-`Class` overloads.
- **Never hardcode the version in Java** — the poms own it; the CLI reads
  `com.garganttua.core.bootstrap.GarganttuaVersion.getVersion()` (Maven-filtered resource).
- READMEs follow `templates/README.md.template`; regenerate with `python3 scripts/run_all.py`.

---

# core — garganttua-core

Foundation framework (base package `com.garganttua.core`): dependency injection, reflection
utilities, expression-language evaluation, scripting, and workflow orchestration. Layered
architecture with strict acyclic dependencies (see `.claude/rules/module-architecture.md`).

### Module layers

1. **Foundation**: `garganttua-commons` (shared interfaces, annotations, exceptions; hosts the
   observability primitives), `garganttua-dsl` (builder framework), `garganttua-supply`
   (supplier/provider), `garganttua-lifecycle` (state), `garganttua-mutex` (locking).
2. **Infrastructure**: `garganttua-reflection` (type-safe binders + `IReflection` facade),
   `garganttua-runtime-reflection` (JVM provider), `garganttua-condition`, `garganttua-execution`
   (chain-of-responsibility), `garganttua-crypto`, `garganttua-configuration`.
3. **Framework**: `garganttua-injection` (DI container), `garganttua-runtime` (workflow engine),
   `garganttua-mapper` (per-source rules), `garganttua-expression` (ANTLR4), `garganttua-bootstrap`,
   `garganttua-properties` (`${VAR:default}`), `garganttua-observability` (`:observe(...)` bridge).
4. **Application**: `garganttua-script` (scripting engine), `garganttua-console` (REPL),
   `garganttua-workflow` (high-level DSL with script generation + observability timing).
5. **Integration**: `garganttua-bindings/` (Spring, Reflections — commented out of reactor).
6. **Build tools**: `garganttua-native-image-maven-plugin`, `garganttua-annotation-processor`
   (commented out of reactor), `garganttua-script-maven-plugin`.
7. **AOT (WIP)**: `garganttua-aot/` parent + `-aot-commons`, `-aot-reflection`,
   `-aot-annotation-scanner`, `-aot-annotation-processor` (per-member source generators),
   `-aot-maven-plugin`.

### Bootstrap SPI (ServiceLoader cold start)

`Bootstrap.builder().autoDetect(true).build()` runs on a cold JVM with zero manual wiring as long as
one `IReflectionProvider` JAR is on the classpath (`garganttua-runtime-reflection` for JVM,
`garganttua-aot-reflection` for native). Provider modules ship
`META-INF/services/com.garganttua.core.reflection.IReflectionProvider` (and `...IAnnotationScanner`)
descriptors; `Bootstrap` sorts by `jakarta.annotation.Priority` (AOT 20, runtime 10, none 0). This
is the cold-start fallback only — `.provide(reflectionBuilder)` overrides it; opt out with
`bootstrap.disableSpiFallback()`.

### Annotation processor & indexing

`garganttua-annotation-processor` (`IndexedAnnotationProcessor`) generates index files in
`META-INF/garganttua/index/` for fast annotation discovery (entries `C:fqcn`, `M:Class#method(...)`).
`@Indexed` annotations (from `garganttua-commons`) plus JSR-330 annotations are indexed. The module
disables annotation processing (`-proc:none`) to avoid self-processing and is pre-installed
separately. **`@Expression` function classes are registered BY FQN** in
`FrameworkBuiltinRegistrar.FRAMEWORK_FUNCTION_CLASSES` — splitting one means adding the new FQNs there.

### Dependency injection

Bean reference format: `[provider::][class][!strategy][#name][@qualifier]`. Key interfaces:
`IInjectionContext`, `IBeanProvider`, `IBeanFactory<T>`, `BeanDefinition<T>` (record). Singleton /
prototype strategies, child contexts, property injection. `@BeanProviderAnnotation("scope")` /
`@PropertyProviderAnnotation("scope")` (both `@Indexed`+`@Reflected`) auto-detected by
`InjectionContextBuilder`. `InjectionContextBuilder` requires an `IReflectionBuilder` build
dependency (tests build one and call `.provide(reflectionBuilder)`).

### Expression & script languages (ANTLR4)

Grammars at `garganttua-expression/src/main/resources/antlr4/Expression.g4` and
`garganttua-script/src/main/resources/antlr4/Script.g4`; never edit generated files under
`target/generated-sources/`. Expression syntax: `concatenate("a","b")`, `:methodName(args)`,
`:(String.class,"value")`, vars `@lazy` / `.eager` / `@0` positional. Nodes implement
`IExpressionNode<R, S extends ISupplier<R>>` → evaluation yields suppliers. Script (`.gs`) adds
`<-` (assign+execute), `=` (assign), `-> exitCode`, `! Ex => handler` / `* Ex => handler`,
`| cond => handler`, statement groups `(...)`, user functions `f = (a,b) => (body)`, `if(c,then[,else])`.
CLI entry `com.garganttua.core.script.Main`; REPL `com.garganttua.core.console.ConsoleMain` (JLine).

### Workflow, runtime, mapper

`garganttua-workflow` (`WorkflowBuilder` → `WorkflowStageBuilder` → `WorkflowScriptBuilder`,
`ScriptGenerator`) generates script from a fluent API. Runtime engine: `@RuntimeDefinition`,
`@Step`/`@Steps`, `@Input`/`@Output`/`@Context`/`@Variable`, `@Catch`/`@FallBack`;
`RuntimeExpressionContext` ThreadLocal uses `push/pop` (not `set/clear`) for nested steps. Mapper:
`@FieldMappingRule`/`@ObjectMappingRule` carry a `source()` attribute and are `@Repeatable`;
`MappingRules.parse(source, destination)` resolves exact > most-specific > wildcard.
**Identifier sanitization**: generated script var names use `name.replaceAll("[^a-zA-Z0-9_]","_")`
— `ScriptGenerator` and `Workflow.collectVariables()` must use the same logic.

### Observability

Primitives live in **`garganttua-commons`** (`com.garganttua.core.observability`) so any layer can
be instrumented without a cycle. Sealed events `StartEvent`/`EndEvent`/`ErrorEvent`; `IObserver<E>`;
`ObservableRegistry<E>` (CopyOnWriteArrayList, exception-isolated); `ObservableContextHolder`
ThreadLocal **stack** (`push`/`pop`); `ObservabilityEmitter` (`open`/`joinCurrent`). Instrumented:
Workflow, Runtime, ScriptContext, Mapper, InjectionContext, Bootstrap, mutex (Expression
deliberately not). `executionId` propagates across engines. `ObservabilityBuilder` DSL wires one
observer to several observables with `garganttua-condition` filters; `ConsoleLogObserver` /
`FileLogObserver` (NDJSON) sinks — the module stays dependency-free (commons/expression/condition/supply).

### Reflection facade & configuration

`IReflection` unifies `IReflectionProvider` (class resolution, prioritized) + `IAnnotationScanner`.
`IClass<T>`/`IMethod`/`IField`/`IConstructor`/`IParameter`/`IRecordComponent` mirror
`java.lang.reflect` for AOT compatibility; `ReflectionBuilder.builder()` → `CompositeReflection`.
Use `FieldAccessor` / `MethodInvoker` for access, not raw `IField.get()` / `IMethod.invoke()`.
`garganttua-configuration` loads JSON/YAML/XML/TOML/Properties (classpath-conditional), recursively
populates DSL builders, and integrates with DI via `ConfigurationPropertyProvider`. A config file can
configure a DSL builder directly via a target *alias* shebang (`#!injection`, `<?garganttua module=…?>`,
`"$module":"…"`); `@ConfigurableBuilder("alias")` opts in; `BootstrapConfigurationContributor` (SPI)
applies discovered files at the bootstrap CONFIGURATION stage when `garganttua-configuration` is present.

### core CI

`.github/workflows/` (now at the reactor root): `maven-publish.yml` (build any branch, deploy to
GitHub Packages on tag), `release.yml` (git-cliff release notes on `v*` tags), and
`build-script-installer.yml` (Linux installer for `garganttua-script`, paths now under `core/`).

---

# api — garganttua-api

Declarative, annotation-driven REST API framework with multi-tenancy, pluggable security, and Spring
Boot / Javalin integration, built on garganttua-core. **Uses garganttua-core injection internally,
not Spring DI** — the starters adapt between the two. `-parameters` is on (parameter names preserved).

### Active modules

- **garganttua-api-bindings** — wrappers around external libs (one submodule per lib).
- **garganttua-api-commons** — pure contract layer (interfaces, `@Entity*`/`@Authentication*`/
  `@Authorization*` annotations, enums, definitions). Everything depends on this.
- **garganttua-api-core** — core engine (legacy `old/`/`legacy/` excluded from compilation).
- **garganttua-api-dao** — DAO abstractions (incl. `garganttua-api-dao-mongodb`).
- **garganttua-api-starters** — Spring Boot / Javalin starters.

Inactive (commented out, Lombok may linger pending migration): `garganttua-api-security/`,
`garganttua-api-interface/`, `garganttua-api-javalin/`, `garganttua-api-native-image/`.

### Key patterns

- **DSL builder** — interfaces in `garganttua-api-commons/context/dsl/`, impls in
  `garganttua-api-core/builder/`; navigate with `up()`, terminal `build()`.
- **Method binder** — wires lifecycle hooks (beforeCreate, afterGet…) and security methods
  (authenticate, sign, validate) at build time via core reflection (`core/builder/binder/`).
- **Pipeline** — `IPipeline` → `IPhase` → `IPhaseScript`; script definitions under
  `garganttua-api-core/src/main/resources/scripts/` (see `PIPELINE.md`). New operation types
  **mirror the CRUD path** — never build a parallel mechanism.
- **Definition / Context separation** — definitions (`EntityDefinition`, `DomainDefinition`) built
  once; contexts (`EntityContext`, `Domain`) aggregate them and provide `invoke(IServiceRequest)`.
- **Suppliers** — every supplier needs a param `@annotation` + an `@Resolver IElementResolver`
  returning its `SupplierBuilder`; carry inter-stage data via workflow-scoped variables / numbered
  argument injection, never ThreadLocal.

### Multi-tenancy roles & characteristics

Roles: **Tenant** (`.tenant(true)`/`@EntityTenant`), **Owner** (`.owner(field)`/`@EntityOwner`),
**Owned** (`.owned(field)`/`@EntityOwned`). Characteristics: **Public** (`.publik()`),
**Geolocalized**, **Hiddenable**, **Shared**. They combine into the access-filter matrix in
`RepositoryFilterTools`. Multi-tenancy disables globally via `ApiBuilder.builder().multiTenant(false)`
(strict: tenant APIs throw `ApiException` when disabled). Fluent request builder: `IDomain.request()`
/ `IApi.request(domainName)` → CRUD shortcuts → `.execute()`.

### Security

Per-domain `.security()`: **Authenticator** (login field, account-status fields, scope;
`AUTHENTICATE.gs` auto-registered), **Authorization** (token type/authorities/expiration, optional
signable/refreshable), **Key** (algorithm/lifetime, placeholder builder). CRUD security runs
`VERIFY_AUTHORIZATION.gs` (access level anonymous/authenticated/tenant/owner). Configuration is
code-first via the `ApiBuilder` DSL / annotations — no Spring `application.properties`.
**Auto-detected markers must be `@Indexed`** (`@Serializer`, `@Protocol`, `@AuthorizationProtocol`,
`@Authentication`, `@Interface`, `@Entity*`) or the AOT index is empty in native. `@Expression`
provider classes are package-scanned by name in the `core.expression` package — keep them there.

### api tests & coverage

JUnit 5 (`@Nested` + `@DisplayName`) + Mockito 5; mock `IInjectionContextBuilder`/`IInjectionContext`
for builder tests; test POJOs (`TestEntity`, `TestDto`) + in-memory DAOs as inner classes. Coverage
floor (active reactor, JaCoCo): **66.4% line / 59.9% branch — do not regress.** Domain names are
auto-generated as plural lowercase of the entity class (`User` → `users`); each domain needs ≥1 DTO.

---

# events — garganttua-events

Pluggable, multi-tenant, multi-cluster event-processing framework built on garganttua-core. Messages
route through configurable expression-based pipelines; **routes compile into garganttua-core
Workflows**, and stages use `@Expression`-annotated functions (not Java processor classes). Package
root `com.garganttua.events`; no `GGEvents` prefix; config via `Map<String,String>`; context as
immutable records.

### Modules

```
events/
├── garganttua-events-api/            interfaces, records, enums, exceptions
├── garganttua-events-expressions/    @Expression functions (protocol_in/out, filter_in/out, …)
├── garganttua-events-core/           engine, DSL builders, route-as-workflow, JSON context
├── garganttua-events-connector-kafka/   Kafka connector
├── garganttua-events-connector-bus/     in-memory BigQueue connector
└── garganttua-events-connector-mail/    email connector (Angus Mail, producer-only)
```

Dependency graph: `api` ← `expressions`, `core`, `connector-*`.

### Architecture

- **api** — `Exchange` (immutable record, `withXxx()` copies) is the message envelope; `ContextDef`,
  `RouteDef`, `RouteStageDef`, `TopicDef`, etc. config records; `IConnector` (SPI extending
  `ILifecycle`), `IEngine`, builder interfaces (`IEngineBuilder` etc. extend `ILinkedBuilder`).
- **core** — `Engine` (extends `AbstractLifecycle`) manages Asset → Tenant → Cluster; each
  `ContextDef` produces a `ClusterRuntime`. `EngineBuilder` extends
  `AbstractAutomaticDependentBuilder` and declares deps on `IInjectionContextBuilder` +
  `IExpressionContextBuilder`. Each `RouteDef` compiles into an `IWorkflow`; stages become inline
  scripts `exchange <- expression(args)`; consumer threads bridge messages to `workflow.execute()`.
  JSON context via `JsonContextReader`/`JsonContextWriter`.
- **connectors** — all extend `AbstractLifecycle` + implement `IConnector`: Kafka (auto topic
  creation, consumer groups), Bus (BigQueue, for testing), Mail (Angus Mail, producer-only).

> The DSL `connector(...)` overloads are honest stubs pending a connector-resolution design decision.
> events now follows the platform conventions (de-Lombok'd, observable `Logger`, Java 25) — any
> remaining Java-21/Lombok/SLF4J note in old docs is stale; the root rules win.
