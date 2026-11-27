# Garganttua API SQLite Binding

## Description

`garganttua-api-binding-sqlite` is a **dependency-only binding module** — it carries no Java sources. It pins the SQLite JDBC driver (`org.xerial:sqlite-jdbc`, which embeds the SQLite library for every platform and ships its own GraalVM native-image feature) and the HikariCP connection pool (`com.zaxxer:HikariCP`) so that every consumer obtains exactly the same artifacts. Their versions live once in the api parent POM (`sqlite-driver.version`, `hikaricp.version`).

**Key Features:**
- **Single-source version pinning** — bump a version in the parent POM, every consumer follows
- **Zero application logic** — no classes, resources or configuration
- **Transitive re-export** — a module depending on this binding gets the driver and the pool on its compile classpath

## Installation

<!-- AUTO-GENERATED-START -->
### Installation with Maven
```xml
<dependency>
    <groupId>com.garganttua</groupId>
    <artifactId>garganttua-api-binding-sqlite</artifactId>
    <version>3.0.0-ALPHA38</version>
</dependency>
```

### Actual version
3.0.0-ALPHA38

### Dependencies
 - `org.xerial:sqlite-jdbc:${sqlite-driver.version}`
 - `com.zaxxer:HikariCP:${hikaricp.version}`

<!-- AUTO-GENERATED-END -->

## Core Concepts

The driver is the database: there is no server to install. `sqlite-jdbc` loads the native SQLite library it embeds for the current platform, and registers `org.sqlite.nativeimage.SqliteJdbcFeature` with GraalVM so that a native image carries that library and the JNI callbacks of the Java functions an application registers.

## Usage

Depend on [`garganttua-api-dao-sqlite`](../../dao/dao-sqlite) or [`garganttua-api-starter-sqlite`](../../starters/starter-sqlite) rather than on this module directly.

## Tips and best practices

- Bump `sqlite-driver.version` in the api parent POM only: the DAO's parity suite runs on the SQLite this driver embeds.

## Parameters

<!-- AUTO-GENERATED-PARAMETERS-START -->
<!-- AUTO-GENERATED-PARAMETERS-END -->

## License
This module is distributed under the MIT License.
