# garganttua-events-core

## Description

Core engine for Garganttua Events. `Engine` (extends `AbstractLifecycle`) manages the
Asset → Tenant → Cluster hierarchy; each `ContextDef` produces a `ClusterRuntime` holding
connectors, workflows, consumers and producers. Each `RouteDef` is compiled into a garganttua-core
`IWorkflow` whose stages become inline scripts (`exchange <- expression(args)`). Ships the DSL
builders (`ContextBuilder`, `RouteBuilder`, …) and JSON context I/O (`JsonContextReader` /
`JsonContextWriter`). Depends on `IInjectionContextBuilder` and `IExpressionContextBuilder`.

## Installation

<!-- AUTO-GENERATED-START -->
### Installation with Maven
```xml
<dependency>
    <groupId>com.garganttua</groupId>
    <artifactId>garganttua-events-core</artifactId>
    <version>3.0.0-ALPHA04</version>
</dependency>
```

### Actual version
3.0.0-ALPHA04

### Dependencies
 - `com.garganttua:garganttua-events-api`
 - `com.garganttua.core:garganttua-commons`
 - `com.garganttua.core:garganttua-dsl`
 - `com.garganttua.core:garganttua-workflow`
 - `com.garganttua.core:garganttua-expression`
 - `com.garganttua.core:garganttua-injection`
 - `com.garganttua.core:garganttua-mutex`
 - `com.garganttua.core:garganttua-lifecycle`
 - `com.garganttua.core:garganttua-script`
 - `com.fasterxml.jackson.core:jackson-databind`
 - `org.projectlombok:lombok`
 - `org.slf4j:slf4j-api`
 - `org.junit.jupiter:junit-jupiter-api`
 - `org.junit.jupiter:junit-jupiter-engine:${junit.version}:test`
 - `com.garganttua.core:garganttua-runtime-reflection:${garganttua.core.version}:test`
 - `com.garganttua.core:garganttua-configuration:test`
 - `com.garganttua:garganttua-events-expressions:${project.version}:test`
 - `com.garganttua.core:garganttua-reflections:${garganttua.core.version}:test`

<!-- AUTO-GENERATED-END -->

## Core Concepts

## Usage

## Tips and best practices

## License
This module is distributed under the MIT License.
