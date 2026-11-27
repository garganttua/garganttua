# Garganttua API SQLite Starter

## Description

`garganttua-api-starter-sqlite` makes an application persist in an embedded SQLite database by its mere presence on the classpath. Discovered through `ServiceLoader`, `SqliteAutoConfiguration` reads `sqlite.*` from the application configuration, opens **one** pooled `DataSource` on the database file, and registers a default DAO factory that gives every domain a `SqliteDao` (table = domain name). A domain that sets its own `.db(...)` keeps it.

**Key Features:**
- **Zero wiring** — add the dependency, set `sqlite.path`
- **No server** — the database is a file, created with its directory when missing
- **One pool** — a single HikariCP pool shared by every domain, closed by the framework on shutdown; every connection in WAL mode, with a busy timeout and foreign keys on
- **Shared schema registry** — every `SqliteDao` it builds shares one `SqlSchemaRegistry`, so `@Composed` references resolve across domains
- **Fail fast** — a file that cannot be opened fails the startup, not the first request

## Installation

<!-- AUTO-GENERATED-START -->
### Installation with Maven
```xml
<dependency>
    <groupId>com.garganttua</groupId>
    <artifactId>garganttua-api-starter-sqlite</artifactId>
    <version>3.0.0-ALPHA38</version>
</dependency>
```

### Actual version
3.0.0-ALPHA38

### Dependencies
 - `com.garganttua:garganttua-api-starter-bootstrap`
 - `com.garganttua:garganttua-api-dao-sqlite`
 - `com.garganttua.core:garganttua-starter-runtime:test`
 - `org.junit.jupiter:junit-jupiter-engine:test`
 - `org.mockito:mockito-core:test`
 - `org.mockito:mockito-junit-jupiter:test`

<!-- AUTO-GENERATED-END -->

## Core Concepts

| Key | Required | Meaning |
|---|---|---|
| `sqlite.path` | one of the two | the database file (`GARGANTTUA_SQLITE_PATH`) |
| `sqlite.url` | one of the two | a JDBC URL, `jdbc:sqlite:/var/lib/app/app.db` |
| `sqlite.pool.size` | no | maximum pooled connections (default 4) |
| `sqlite.busy.timeout` | no | how long a writer waits for the write lock, in ms (default 10000) |
| `sqlite.schema.auto` | no | `true` (default): create missing tables and columns; `false`: create nothing, refuse a schema that does not fit |
| `sqlite.index.auto` | no | `create` (default), `none` or `strict` — the contract of `mongodb.index.auto` |

A private in-memory URL (`jdbc:sqlite::memory:`) is refused: every pooled connection would see its own, empty database.

## Usage

```yaml
api:
  packages: com.example.myapp
sqlite:
  path: /var/lib/myapp/myapp.db
  index:
    auto: strict
```

## Tips and best practices

- Put the file on a local disk: SQLite's locking is unreliable on network file systems.
- Back the database up with SQLite's online backup (`VACUUM INTO 'backup.db'`), not by copying a file that is being written.

## Parameters

<!-- AUTO-GENERATED-PARAMETERS-START -->
<!-- AUTO-GENERATED-PARAMETERS-END -->

## License
This module is distributed under the MIT License.
