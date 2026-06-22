# Module Architecture
---
paths:
  - "**/pom.xml"
  - "**/*.java"
---

## Layer Hierarchy (Strict Acyclic Dependencies)

### Bootstrap SPI Cold Start

- `Bootstrap`'s constructor uses `ServiceLoader` to discover `IReflectionProvider` and `IAnnotationScanner` implementations declared in `META-INF/services/` of provider JARs.
- Default priorities: AOT modules at 20, runtime modules at 10, no annotation at 0 (last). Annotation: `jakarta.annotation.Priority`.
- **Shade plugins must include `ServicesResourceTransformer`** to merge service descriptors when producing executable fat JARs — critical for native-image.
- Opt-out: `bootstrap.disableSpiFallback()`.

### 1. Foundation
- `garganttua-commons` - shared interfaces, annotations, exceptions; hosts the observability primitives (`IObservable`, `IObserver`, `ObservableRegistry`, `ObservableContextHolder`, `ObservabilityEmitter`, sealed `ObservableEvent` family) so any layer can be instrumented without dependency cycles
- `garganttua-dsl` - builder framework
- `garganttua-supply` - supplier/provider pattern
- `garganttua-lifecycle` - state management
- `garganttua-mutex` - locking primitives

### 2. Infrastructure
- `garganttua-reflection` - type-safe reflection binders + composite `IReflection` facade
- `garganttua-runtime-reflection` - JVM runtime reflection provider (`RuntimeReflectionProvider`)
- `garganttua-condition` - boolean condition DSL
- `garganttua-execution` - chain-of-responsibility
- `garganttua-crypto` - cryptographic utilities
- `garganttua-configuration` - multi-format config loading & builder population

### 3. Framework
- `garganttua-injection` - DI container (auto-detects `@BeanProviderAnnotation` / `@PropertyProviderAnnotation`)
- `garganttua-runtime` - workflow engine
- `garganttua-mapper` - object mapping (supports per-source rules via `source()` attribute)
- `garganttua-expression` - ANTLR4 expression language
- `garganttua-bootstrap` - application bootstrapping
- `garganttua-properties` - `.properties` file provider with `${VAR:default}` placeholders
- `garganttua-observability` - script-side `:observe(...)` expression bridge (the observer primitives themselves live in `garganttua-commons`)

### 4. Application
- `garganttua-script` - scripting engine (runtime construction delegated to `RuntimesBuilder`)
- `garganttua-console` - interactive REPL (extracted from script)
- `garganttua-workflow` - high-level workflow DSL with script generation and observability timing

### 5. Integration
- `garganttua-bindings/` - Spring, Reflections library bindings (commented out of reactor)

### 6. Build Tools
- `garganttua-native-image-maven-plugin` - native image Maven plugin
- `garganttua-annotation-processor` - compile-time annotation indexing (commented out of reactor)
- `garganttua-script-maven-plugin` - script plugin JAR packaging

### 7. AOT (Work in Progress)
- `garganttua-aot/` parent with submodules:
  - `garganttua-aot-commons` - shared AOT interfaces
  - `garganttua-aot-reflection` - pre-generated `IClass<T>` descriptors
  - `garganttua-aot-annotation-scanner`
  - `garganttua-aot-annotation-processor` - compile-time code generator (refactored into per-member source generators)
  - `garganttua-aot-maven-plugin`

## Critical Rules

- **All modules depend on `garganttua-commons`**
- **Circular dependencies are strictly forbidden**
- Respect layer boundaries - lower layers cannot depend on higher layers

## Key Dependency Chains

```
injection → lifecycle, supply, dsl, reflection, reflections, native
runtime → injection, execution, condition
expression → injection
script → expression, runtime, bootstrap, condition, mutex, annotation-processor
console → script, expression, injection, bootstrap, annotation-processor, mutex, reflections
workflow → script, expression, injection, dsl, observability
properties → commons, injection
observability → commons, expression
configuration → commons, dsl, reflection, jackson-databind; injection (provided)
runtime-reflection → commons
reflection → commons, supply
```
