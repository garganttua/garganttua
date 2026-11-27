# Garganttua API SQLite DAO

## Description

`garganttua-api-dao-sqlite` provides the **SQLite-backed `IDao` implementation** for the Garganttua API framework: the relational mapping of the shared SQL core ([`garganttua-api-dao-sql`](../dao-sql)) — **one column per field, one child table per collection** — on an embedded SQLite database file, behaving exactly like the MongoDB DAO.

"Exactly" is measured, not claimed: the differential parity suite of the PostgreSQL DAO — the same DTOs, the same data, the same filter, sort, page and projection, against a real `mongod` — runs here too, against SQLite, and demands identical answers. It passes in full; the residuals it pins are the PostgreSQL DAO's, minus one (see *Known differences*).

**Key Features:**
- **`IDao` implementation** — `SqliteDao`: `find` (filter, sort, page, projection), `save` (upsert), `delete`, `count`, `registerDomain`
- **The same mapping as PostgreSQL** — scalars become typed columns, embedded POJOs are flattened, collections and maps become child tables at any depth, `@Composed` references are stored as uuids; the tables have the same names and columns
- **MongoDB semantics** — the full filter language (`$and` `$or` `$nor`, `$eq` `$ne` `$gt` `$gte` `$lt` `$lte` `$in` `$nin` `$regex` `$empty` `$text` `$geoWithin` `$geoWithinSphere` `$geoIntersects` `$near` `$nearSphere`), BSON type bracketing and order, array "any element" semantics, absent vs null
- **No server** — the database is a file; the DAO needs nothing but `sqlite-jdbc`, which embeds SQLite (3.54) for every platform and carries its own GraalVM feature
- **Few round trips** — a read is one statement, collections included (aggregated as JSON by common table expressions)
- **Atomic writes** — a write that spans several tables runs in one `IMMEDIATE` transaction: it takes the write lock up front, so two writers queue on the busy timeout instead of one failing on a lock upgrade
- **Schema and index management** — tables, later-added columns and declared indexes are created at startup (`SqliteSchemaMode.CREATE`), or only checked (`VALIDATE`); a unique violation answers **409**
- **AOT-ready** — `SqliteDao` is `@Reflected` and seeded in `AOTRegistry` by `SqliteDaoInfrastructureSeed`; the native proof [`e2e/native-sqlite`](../../e2e/native-sqlite) runs it as a GraalVM binary

## Installation

<!-- AUTO-GENERATED-START -->
### Installation with Maven
```xml
<dependency>
    <groupId>com.garganttua</groupId>
    <artifactId>garganttua-api-dao-sqlite</artifactId>
    <version>3.0.0-ALPHA38</version>
</dependency>
```

### Actual version
3.0.0-ALPHA38

### Dependencies
 - `com.garganttua:garganttua-api-commons`
 - `com.garganttua:garganttua-api-dao-sql`
 - `com.garganttua:garganttua-api-binding-sqlite`
 - `com.garganttua:garganttua-api-binding-geojson`
 - `com.garganttua:garganttua-api-binding-jackson`
 - `com.garganttua.core:garganttua-crypto`
 - `com.garganttua.core:garganttua-aot-reflection`
 - `com.garganttua.core:garganttua-aot-commons`
 - `org.junit.jupiter:junit-jupiter-engine:test`
 - `com.garganttua.core:garganttua-bootstrap:test`
 - `com.garganttua.core:garganttua-runtime-reflection:test`
 - `com.garganttua.core:garganttua-reflections:test`
 - `com.garganttua:garganttua-api-dao-mongodb:${project.version}:test`
 - `com.garganttua:garganttua-api-dao-postgresql:${project.version}:test`
 - `com.garganttua:garganttua-api-dao-postgresql:${project.version}:test`
 - `org.mockito:mockito-core:test`
 - `org.mockito:mockito-junit-jupiter:test`

<!-- AUTO-GENERATED-END -->

## Core Concepts

### How values are stored

SQLite has five storage classes and compares values by class, then by value. Each logical type of the table model is stored so that SQLite's own comparison orders it the way BSON does — so filters and sorts on scalar columns are plain SQL, and use the indexes:

| Java type | SQLite storage | Why |
|---|---|---|
| `String`, `char`, enums, `UUID` | `TEXT` | compared byte by byte (UTF-8), BSON's string order |
| `int`, `long`, `short`, `byte` | `INTEGER` | |
| `boolean` | `INTEGER` 0 / 1 | |
| `double`, `float` | a `REAL` in a column **without affinity** | a `REAL` column stores an integral value as an integer, and `-0.0` would come back `0.0` |
| `NaN` | the text `'NaN'` | SQLite turns a bound NaN into NULL; the comparisons exclude it as BSON does |
| `BigDecimal`, `BigInteger` | its exact text (`1.50` keeps its scale) | compared and indexed by `CAST(… AS NUMERIC)` — by value, as `Decimal128` |
| `Instant`, `Date`, `OffsetDateTime`, `ZonedDateTime`, `LocalDateTime` | `INTEGER` microseconds since the epoch, UTC | integers order as the instants |
| `LocalDate` / `LocalTime` | `INTEGER` days since the epoch / microseconds of the day | |
| `byte[]` | `BLOB` | |
| untyped / recursive values, `IKey` | JSON `TEXT` | |
| GeoJSON geometries | GeoJSON `TEXT` | read back by `GeoJsonCodec` |

### What runs in Java

SQLite runs inside the JVM. What its SQL cannot say is a Java function, registered on each connection the first time the DAO uses it (`SqliteFunctions` — once per physical connection, remembered weakly), and called back by SQLite:

- **The BSON order of a structure** — sorting on an embedded document, an array or a map. PostgreSQL sorts on row values; SQLite cannot, so each value becomes a text key (`SqliteOrderKey`, `gg_okey`) whose binary order is BSON's: type rank first, numbers by exact value (`2^53` below `2^53 + 1`), `NaN` below every number, texts by UTF-8 bytes, binaries by length first, a sequence that is a prefix of another before it. A path through nested collections is followed by joins, SQLite having no `LATERAL`.
- **`$regex`** — `java.util.regex` given PCRE's meaning: `UNIX_LINES` (only `\n` ends a line for `.`, `$`, `(?m)`), `UNICODE_CASE` for `(?i)`, `\w` `\d` `\s` `\b` ASCII as in MongoDB. A construct Java does not know (recursion, conditionals) is refused when the filter is built, naming the field.
- **`$text`** — the words of the document, folded, stemmed by the Snowball English stemmer (MongoDB's and PostgreSQL's `english`), PostgreSQL's English stop words removed; terms OR-ed, `-exclusions`, `"phrases"`. Over every string of the entity — collections, JSON values and geometry types included — or over the field of a declared `text` index.
- **Geography** — on the sphere, as a `2dsphere` index: every edge is a great-circle arc, distances are measured on MongoDB's sphere (radius 6 378 100 m). `$geoWithin` is PostGIS's `ST_CoveredBy` (boundary included, holes excluded, a line leaving a concave shape is not within), `$geoIntersects` any shared point, `$near` nearest first with `$maxDistance` / `$minDistance` in metres.

### `SqliteDao`

One DAO per domain, on the caller's `DataSource`; its main table is named after the domain. `registerDomain` builds the table model, creates or checks the tables (`SqliteSchemaManager`), creates the declared indexes (`SqliteIndexManager`), and wires the shared core's writer, reader and query builder with the `SqliteDialect`.

### Schema modes

- `SqliteSchemaMode.CREATE` (default) — creates missing tables and **adds** columns a DTO gained since; never drops, renames or retypes anything (a stored type that differs is a `WARN`). Runs in one `IMMEDIATE` transaction, so two processes registering one domain on one file do it one after the other.
- `SqliteSchemaMode.VALIDATE` — issues no DDL; refuses to start on a missing table or column, or a column of another type, and gives the statement that fixes it.

### Indexes

Declared with `entity().index(...)` or `@EntityIndexed`, created at startup under `SqliteIndexMode` (`sqlite.index.auto`: `create`, `none`, `strict`) — the planning is the shared core's (`SqlIndexPlanner`), so the same rules hold as on PostgreSQL:

- `standard` — a B-tree, `(tenantId, field)` when tenant-scoped; a unique one is partial (`WHERE field IS NOT NULL`), so any number of entities may leave the field unset; a decimal is indexed on `CAST(field AS NUMERIC)`, so a unique `1.50` refuses `1.5`. A field held in a collection is indexed on its child table; a unique index there is refused.
- `geo` and `text` — SQLite has no index that would give the same answers; the filters they serve are evaluated by the Java functions, row by row. The declaration is accepted — a text index still chooses the field `$text` searches — and nothing is created.

The entity's tenant is indexed without being declared, as on PostgreSQL.

### Known differences

- **An `Object`-typed field holding a `Long`** — stored as JSON, where there is one number type: `5L` reads back as an `Integer`, as on PostgreSQL. Typed fields are unaffected.
- **The NUL character (`\u0000`)** is stored — unlike PostgreSQL, SQLite `TEXT` holds it.
- The residual inherited from the shared core: whether an embedded POJO of a collection element exists is not tracked, and a null element of a nested list is not told apart from an absent value (`lines.product $eq null`).

## Usage

The simplest wiring is the starter: add [`garganttua-api-starter-sqlite`](../../starters/starter-sqlite) and set `sqlite.path`. To wire a domain by hand:

```java
SQLiteConfig config = new SQLiteConfig();
config.setJournalMode(SQLiteConfig.JournalMode.WAL);   // readers never wait for the writer
config.setBusyTimeout(10_000);                         // a second writer waits instead of failing
config.enforceForeignKeys(true);
SQLiteDataSource dataSource = new SQLiteDataSource(config);
dataSource.setUrl("jdbc:sqlite:/var/lib/myapp/myapp.db");

SqlSchemaRegistry registry = new SqlSchemaRegistry();  // shared: lets a @Composed field reach another domain

ApiBuilder.builder()
    .domain(User.class)
        .entity().id("id").uuid("uuid").tenantId("tenantId").up()
        .dto(UserDto.class)
            .id("id").uuid("uuid").tenantId("tenantId")
            .db(new SqliteDao(dataSource, "users", registry, SqliteSchemaMode.CREATE))
        .up()
    .up()
    .build();
```

## Tips and best practices

- **Use WAL mode and a busy timeout** — the starter sets both. Without WAL, a reader blocks the writer; without a busy timeout, a second writer fails at once instead of waiting.
- **Share one `SqlSchemaRegistry` across the domains of a database**, for `@Composed` fields.
- **Keep the pool small** (the starter defaults to 4): SQLite writes one transaction at a time; more connections only serve concurrent readers.
- **A filter on a geometry or `$text` scans** the rows its other conditions leave: combine it with an indexed condition (the tenant is one) on a large table.
- **Do not open the file from a process that has not registered the DAO's functions** for anything but reads of plain columns: a sort on a structure calls them.

## Parameters

<!-- AUTO-GENERATED-PARAMETERS-START -->
<!-- AUTO-GENERATED-PARAMETERS-END -->

## License
This module is distributed under the MIT License.
