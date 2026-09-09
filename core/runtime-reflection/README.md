# Garganttua Runtime Reflection

## Description

Runtime reflection utilities for Garganttua Core. This module provides `RuntimeReflectionProvider`, the standard JVM runtime implementation of the `IReflectionProvider` interface defined in `garganttua-commons`. It uses `java.lang.reflect` to resolve classes, inspect fields, methods, constructors, and annotations at runtime.

This is the default reflection provider used throughout the framework. Alternative providers (e.g., AOT-based) can be composed alongside it via `ReflectionBuilder` with priority-based selection.

## Installation

<!-- AUTO-GENERATED-START -->
### Installation with Maven
```xml
<dependency>
    <groupId>com.garganttua.core</groupId>
    <artifactId>garganttua-runtime-reflection</artifactId>
    <version>3.0.0-ALPHA19</version>
</dependency>
```

### Actual version
3.0.0-ALPHA19

### Dependencies
 - `com.garganttua.core:garganttua-commons`

<!-- AUTO-GENERATED-END -->

## Core Concepts

## Usage

```bash
mvn clean install -pl garganttua-runtime-reflection
```

## Tips and best practices

## Parameters

<!-- AUTO-GENERATED-PARAMETERS-START -->
Parameters this module declares or reads. Pass them with `-D` — on the JVM running the application for runtime scope, on the Maven command line for build scope.

| Parameter | Scope | Values | Default | Effect |
|---|---|---|---|---|
| `-Dgarganttua.direct.binders=true` | Build (Maven / APT) | `true` \| `false` | `false` (set in `core/pom.xml`; most function modules override it to `true`) | Maven property forwarded to javac as `-Agarganttua.direct.binders` and consumed by `garganttua-aot-annotation-processor`. When on, the module ships compile-time `AOTClass_*` descriptors for its `@Reflected` classes — required for a native build to see them. Modules inside the `aot-commons`/`aot-reflection` dependency cycle must stay `false`. |
<!-- AUTO-GENERATED-PARAMETERS-END -->

## License
This module is distributed under the MIT License.
