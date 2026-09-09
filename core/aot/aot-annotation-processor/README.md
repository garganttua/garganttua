# Garganttua AOT Annotation Processor

## Description

Compile-time annotation processor for generating AOT class descriptors. This processor runs during compilation to produce metadata files that describe annotated classes, enabling ahead-of-time resolution without runtime classpath scanning.

**Note**: This module disables annotation processing (`-proc:none`) in its own build to avoid self-processing.

## Installation

<!-- AUTO-GENERATED-START -->
### Installation with Maven
```xml
<dependency>
    <groupId>com.garganttua.core</groupId>
    <artifactId>garganttua-aot-annotation-processor</artifactId>
    <version>3.0.0-ALPHA19</version>
</dependency>
```

### Actual version
3.0.0-ALPHA19

### Dependencies
 - `com.garganttua.core:garganttua-commons`
 - `com.garganttua.core:garganttua-observability:test`
 - `org.junit.jupiter:junit-jupiter-engine:test`

<!-- AUTO-GENERATED-END -->

## Core Concepts

## Usage

```bash
mvn clean install -pl garganttua-aot/garganttua-aot-annotation-processor
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
