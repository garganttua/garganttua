# Garganttua API PostgreSQL Starter

## Description

`garganttua-api-starter-postgresql` makes an application persist in PostgreSQL by its mere presence on the classpath. Discovered through `ServiceLoader`, `PostgresAutoConfiguration` reads `postgresql.*` from the application configuration, opens **one** pooled `DataSource`, and registers a default DAO factory that gives every domain a `PgDao` (table = domain name). A domain that sets its own `.db(...)` keeps it.

**Key Features:**
- **Zero wiring** — add the dependency, set `postgresql.url`
- **One pool** — a single HikariCP pool shared by every domain, closed by the framework on shutdown
- **Shared schema registry** — every `PgDao` it builds shares one `PgSchemaRegistry`, so `@Composed` references resolve across domains
- **Fail fast** — an unreachable server fails the startup, not the first request

## Installation

<!-- AUTO-GENERATED-START -->
### Installation with Maven
```xml
<dependency>
    <groupId>com.garganttua</groupId>
    <artifactId>garganttua-api-starter-postgresql</artifactId>
    <version>3.0.0-ALPHA24</version>
</dependency>
```

### Actual version
3.0.0-ALPHA24

### Dependencies
 - `com.garganttua:garganttua-api-starter-bootstrap`
 - `com.garganttua:garganttua-api-dao-postgresql`
 - `com.garganttua.core:garganttua-starter-runtime:test`
 - `org.junit.jupiter:junit-jupiter-engine:test`
 - `org.mockito:mockito-core:test`
 - `org.mockito:mockito-junit-jupiter:test`

<!-- AUTO-GENERATED-END -->

## Core Concepts

| Key | Required | Meaning |
|---|---|---|
| `postgresql.url` | yes | JDBC URL, `jdbc:postgresql://host:5432/db` (or `GARGANTTUA_POSTGRESQL_URL`) |
| `postgresql.user` | no | user name |
| `postgresql.password` | no | password |
| `postgresql.pool.size` | no | maximum pooled connections (default 10) |
| `postgresql.schema.auto` | no | `true` (default): create missing tables and columns — `false`: create nothing, refuse a schema that does not fit |

The auto-configuration runs at `order() = 0`: persistence is wired before transport.

## Usage

```yaml
postgresql:
  url: jdbc:postgresql://localhost:5432/myapp
  user: app
  password: ${POSTGRES_PASSWORD}
```

## Tips and best practices

- Set `postgresql.schema.auto: false` where the schema is migrated by a reviewed process: the application then checks the schema and changes nothing.
- Keep `postgresql.pool.size` below the server's `max_connections` divided by the number of instances.

## Parameters

<!-- AUTO-GENERATED-PARAMETERS-START -->
<!-- AUTO-GENERATED-PARAMETERS-END -->

## License
This module is distributed under the MIT License.
