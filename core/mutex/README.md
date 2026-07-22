# Garganttua Mutex

## Description

The Garganttua Mutex module provides a thread-safe mutex synchronization framework for managing mutually exclusive access to critical sections. It supports configurable acquisition strategies including timeout, retry logic, and automatic lease expiration for robust distributed locking scenarios.

## Installation

<!-- AUTO-GENERATED-START -->
### Installation with Maven
```xml
<dependency>
    <groupId>com.garganttua.core</groupId>
    <artifactId>garganttua-mutex</artifactId>
    <version>3.0.0-ALPHA11</version>
</dependency>
```

### Actual version
3.0.0-ALPHA11

### Dependencies
 - `com.garganttua.core:garganttua-aot-reflection`
 - `com.garganttua.core:garganttua-aot-commons`
 - `com.garganttua.core:garganttua-commons`
 - `com.garganttua.core:garganttua-dsl`
 - `com.garganttua.core:garganttua-injection`
 - `com.garganttua.core:garganttua-runtime-reflection:test`
 - `com.garganttua.core:garganttua-reflections:test`

<!-- AUTO-GENERATED-END -->

## Core Concepts



### Key Features


## Usage



### Usage Patterns


### Exception Handling

## Tips and best practices

1. **Use named mutexes**: Consistently name mutexes based on the resource they protect (e.g., "db:user:123", "cache:session:abc")
2. **Configure appropriate timeouts**: Use realistic wait times based on expected operation duration
3. **Set lease times**: Always configure lease time to prevent deadlocks when holders fail
4. **Handle MutexException**: Always catch and handle MutexException appropriately for your use case
5. **Keep critical sections short**: Minimize the code executed within mutex.acquire() to reduce lock contention
6. **Use strategy-based acquisition**: For production systems, prefer strategy-based acquisition over simple acquisition
7. **Test retry logic**: Verify that retry configuration works correctly under failure scenarios
8. **Avoid nested mutexes**: Be careful when acquiring multiple mutexes to prevent deadlocks
9. **Monitor mutex contention**: Log mutex acquisition failures to identify performance bottlenecks

## Parameters

<!-- AUTO-GENERATED-PARAMETERS-START -->
Parameters this module declares or reads. Pass them with `-D` — on the JVM running the application for runtime scope, on the Maven command line for build scope.

| Parameter | Scope | Values | Default | Effect |
|---|---|---|---|---|
| `-Dgarganttua.direct.binders=true` | Build (Maven / APT) | `true` \| `false` | `false` (set in `core/pom.xml`; most function modules override it to `true`) | Maven property forwarded to javac as `-Agarganttua.direct.binders` and consumed by `garganttua-aot-annotation-processor`. When on, the module ships compile-time `AOTClass_*` descriptors for its `@Reflected` classes — required for a native build to see them. Modules inside the `aot-commons`/`aot-reflection` dependency cycle must stay `false`. |
<!-- AUTO-GENERATED-PARAMETERS-END -->

## License
This module is distributed under the MIT License.
