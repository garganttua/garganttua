# garganttua-events-expressions

## Description

`@Expression`-annotated static functions for event processing, auto-discovered by garganttua-core's
`ExpressionContextBuilder`. Includes `protocol_in` / `protocol_out` (envelope (de)serialization),
`filter_in` / `filter_out` (origin/destination policy filtering), and `set_header`, `get_header`,
`json_path`, `log`, `produce`, `route_to_error`, `not_null`. Route stages reference these functions
instead of Java processor classes.

## Installation

<!-- AUTO-GENERATED-START -->
### Installation with Maven
```xml
<dependency>
    <groupId>com.garganttua</groupId>
    <artifactId>garganttua-events-expressions</artifactId>
    <version>3.0.0-ALPHA04</version>
</dependency>
```

### Actual version
3.0.0-ALPHA04

### Dependencies
 - `com.garganttua:garganttua-events-api`
 - `com.garganttua.core:garganttua-commons`
 - `com.garganttua.core:garganttua-expression`
 - `com.fasterxml.jackson.core:jackson-databind`
 - `com.jayway.jsonpath:json-path`
 - `org.slf4j:slf4j-api`
 - `org.junit.jupiter:junit-jupiter-api`

<!-- AUTO-GENERATED-END -->

## Core Concepts

## Usage

## Tips and best practices

## License
This module is distributed under the MIT License.
