# garganttua-events-api

## Description

Pure API layer for Garganttua Events: interfaces, Java records and enums shared by every events
module. Holds the `Exchange` message envelope (immutable, `withXxx()` copies), the configuration
records (`ContextDef`, `RouteDef`, `RouteStageDef`, `TopicDef`, `ConnectorDef`, …), the `IConnector`
SPI, `IEngine`, and the builder interfaces (`IEngineBuilder`, `IContextBuilder`, `IRouteBuilder`, …).
Zero business logic.

## Installation

<!-- AUTO-GENERATED-START -->
### Installation with Maven
```xml
<dependency>
    <groupId>com.garganttua</groupId>
    <artifactId>garganttua-events-api</artifactId>
    <version>3.0.0-ALPHA04</version>
</dependency>
```

### Actual version
3.0.0-ALPHA04

### Dependencies
 - `com.garganttua.core:garganttua-commons`
 - `com.garganttua.core:garganttua-lifecycle`
 - `com.fasterxml.jackson.core:jackson-databind`
 - `com.github.spotbugs:spotbugs-annotations:4.9.8:provided`

<!-- AUTO-GENERATED-END -->

## Core Concepts

## Usage

## Tips and best practices

## License
This module is distributed under the MIT License.
