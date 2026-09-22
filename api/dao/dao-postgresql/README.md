# Garganttua API PostgreSQL DAO

## Description

`garganttua-api-dao-postgresql` provides the **PostgreSQL-backed `IDao` implementation** for the Garganttua API framework: a relational mapping — **one column per field** — that behaves exactly like the MongoDB DAO.

"Exactly" is measured, not claimed: a differential parity suite runs **403 scenarios** against a real `mongod` and a real PostgreSQL 17 — the same DTOs, the same data, the same filter, sort, page and projection — and demands identical answers from both. Two divergences remain, both impossible to remove, both pinned by tests (see *Known differences*).

**Key Features:**
- **`IDao` implementation** — `PgDao`: `find` (filter, sort, page, projection), `save` (upsert), `delete`, `count`, `registerDomain`
- **Relational mapping** — scalars become typed columns, embedded POJOs are flattened into prefixed columns, collections and maps become child tables; see *Core Concepts*
- **MongoDB semantics** — the full filter language (`$and` `$or` `$nor`, `$eq` `$ne` `$gt` `$gte` `$lt` `$lte` `$in` `$nin` `$regex` `$empty` `$text` `$geoWithin` `$geoWithinSphere`) with MongoDB's own rules: BSON type bracketing, array "any element" semantics, absent vs null, binary string order, PCRE regular expressions, text search over every string value
- **Atomic writes, consistent reads** — every write runs in one transaction; every read runs in one `REPEATABLE READ` snapshot, however many tables it touches
- **Schema management** — tables and later-added columns are created at startup (`SchemaMode.CREATE`, the default), or only checked (`SchemaMode.VALIDATE`); safe when several instances start at once
- **Compositions** — `@Composed` fields are stored as the referenced uuid and resolved one level deep, like MongoDB's DBRefs
- **`IKey` persistence** — crypto key material stored as the same self-describing descriptor the MongoDB DAO uses
- **AOT-ready** — `PgDao` is `@Reflected` and seeded in `AOTRegistry` by `PostgresDaoInfrastructureSeed`

## Installation

<!-- AUTO-GENERATED-START -->
### Installation with Maven
```xml
<dependency>
    <groupId>com.garganttua</groupId>
    <artifactId>garganttua-api-dao-postgresql</artifactId>
    <version>3.0.0-ALPHA22</version>
</dependency>
```

### Actual version
3.0.0-ALPHA22

### Dependencies
 - `com.garganttua:garganttua-api-commons`
 - `com.garganttua:garganttua-api-binding-postgresql`
 - `com.garganttua:garganttua-api-binding-jackson`
 - `com.garganttua.core:garganttua-crypto`
 - `com.garganttua.core:garganttua-aot-reflection`
 - `com.garganttua.core:garganttua-aot-commons`
 - `org.junit.jupiter:junit-jupiter-engine:test`
 - `com.garganttua.core:garganttua-bootstrap:test`
 - `com.garganttua.core:garganttua-runtime-reflection:test`
 - `com.garganttua.core:garganttua-reflections:test`
 - `com.garganttua:garganttua-api-dao-mongodb:${project.version}:test`
 - `io.zonky.test:embedded-postgres:test`
 - `org.mockito:mockito-core:test`
 - `org.mockito:mockito-junit-jupiter:test`

<!-- AUTO-GENERATED-END -->

## Core Concepts

### How a DTO becomes tables

The shape of a domain's tables is derived once from its DTO by `PgSchemaModel`, and every part of the DAO — DDL, writer, reader, query translation — reads that single model.

| DTO field | PostgreSQL |
|---|---|
| the uuid field | `TEXT PRIMARY KEY` |
| a scalar (`String`, numbers, `boolean`, `enum`, `Instant`, `LocalDate`, `UUID`, `byte[]`, …) | one typed column |
| an embedded POJO | its fields, flattened with a prefix (`address__city`) |
| a list / set of scalars or of POJOs | a child table `<table>__<field>`, one row per element |
| a map with scalar keys | a child table keyed by `_key` |
| a list / set / map inside a collection element (`List<Order>` whose `Order` holds `List<Line>`, `List<List<String>>`, `Map<String, List<Book>>`) | a nested child table of the element's table, at any depth |
| a `@Composed` reference | a `TEXT` column holding the referenced uuid (a child table for a collection) |
| `IKey` | a `JSONB` key descriptor |
| a GeoJSON geometry | a PostGIS `geometry(Geometry, 4326)` column |
| anything with no finite relational shape | a `JSONB` column |

The `JSONB` fallback covers what relational storage cannot express: a POJO that contains itself (`Node next`) would flatten into infinitely many columns, and an untyped `List<Object>` has no column type.

**Nested tables.** A collection inside a collection element is not a `JSONB` value: it is a child table of the element's table, and so on down, with no depth limit (`shops` → `shops__orders` → `shops__orders__lines`). Every child table carries `_owner`, the root entity's uuid (`ON DELETE CASCADE`), and is keyed by `_ord` (lists) or `_key` (maps). A table whose elements have children of their own also gets `_id BIGINT GENERATED ALWAYS AS IDENTITY`, and the rows beneath point at their element through `_parent` (`ON DELETE CASCADE`), so replacing or deleting an entity only touches its top-level rows. An element that is itself a collection (`List<List<String>>`, `Map<String, List<Book>>`) gets a table of its own, suffixed `___e`.

- **Writes** go one table at a time, one batch per table; the generated `_id`s of a level become the `_parent`s of the next, all in the caller's transaction.
- **Reads** run one query per table of the tree, for the whole page at once (`_owner = ANY(...)`), and rebuild each element bottom-up before it enters its parent collection.
- **Filters** cross every array level MongoDB's way: `orders.lines.sku $eq "K"` matches when *any* order has *any* line whose sku matches (one `EXISTS` per level), `$ne` / `$nin` when none does; a map is crossed by its key (`stock.paris.origin.labels`).
- **Sort** takes the smallest (asc) or largest (desc) value reachable across all levels, a map entry included (`stock.paris.qty`); sorting on a whole structure that holds a nested collection (`orders.lines`) is refused. `$text` searches every table of the tree.
- **Not yet reproduced** inside a child element: whether an embedded POJO of the element exists (`lines.product $eq null`, `lines.product.brand $ne null`) is not tracked, so such a filter treats it as absent; and a null element of a nested list (`orders.lines = [null]`) is not told apart from an absent value.

**Presence columns.** Flattening loses a fact MongoDB keeps: whether a structure *existed*. A null list and an empty one would both be zero child rows. Every flattened POJO, collection, map and reference collection therefore carries a `BOOLEAN` presence column on its owner, and every POJO element a `_present` column — so `null`, `[]` and `{}` stay distinct in filters and on the way back.

Identifiers are always double-quoted (reserved words such as `order` are valid field names) and kept under PostgreSQL's 63-byte limit by a hash suffix — PostgreSQL would otherwise truncate them silently.

### `PgDao`

Wires four collaborators around the model: `PgSchemaManager` (DDL), `PgWriter` (upsert, delete), `PgReader` (select, rebuild) and `PgQueryBuilder` (filters, sort, pagination, projection). A read is several queries — the main table, then each child table — which is why it runs in one `REPEATABLE READ` snapshot: without it, a write committed between two of them would return an entity stitched from two moments.

### Schema modes

- `SchemaMode.CREATE` (default) — creates missing tables and **adds** columns a DTO gained since; never drops, renames or retypes anything. A stored type that differs from the model is logged as a `WARN`. Everything runs in one transaction under an advisory lock, so instances starting together do not race.
- `SchemaMode.VALIDATE` — issues no DDL at all; refuses to start on a schema that does not fit, and lists every problem with the DDL that would fix it.

A geometry field needs the PostGIS extension; without it, `CREATE` mode stops with a message naming the field and how to install PostGIS.

### MongoDB semantics, reproduced

| Behaviour | As on MongoDB |
|---|---|
| filter value types | a value only compares with a field of the same type class: `{"age":"18"}` never matches a number, a JSON date string never matches an `Instant` |
| numbers | compared exactly across widths (`18.5` against an `int` field is `18.5`) |
| arrays | `$eq` / `$in` / ranges / `$regex` match when **any** element does; `$ne` / `$nin` when **none** does |
| absent fields | an unknown field is absent: `$eq x` finds nothing, `$ne x` finds everything |
| `null` | a stored null is absent: a field initialiser survives a read |
| strings | binary code-point order (`COLLATE "C"`), whatever the database collation |
| sort | null first ascending; arrays by their smallest (asc) or largest (desc) element; `NaN` below every number |
| pages | the uuid is appended as a final ascending sort key, so pages over a non-unique key are stable |
| dates | milliseconds, as BSON keeps them |
| `$regex` | PCRE: `\b`, `\A`, `\z`, `$` before a final newline, `.` not matching a newline — translated to PostgreSQL's engine; a construct it cannot express is refused by name, never matched differently |
| `$text` | a `$**` text index: terms are OR-ed, `"phrases"`, `-exclusions`, English stemming, case- and accent-insensitive, over every string value |
| numbers beyond 34 digits | refused, as MongoDB's `Decimal128` cannot hold them |

### Known differences

Two divergences cannot be removed, and are pinned by tests that will fail if they ever change:

- **The NUL character (`\u0000`)** — PostgreSQL `TEXT` and `JSONB` cannot store it. MongoDB does; PostgreSQL refuses the save with an error naming the field.
- **An `Object`-typed field holding a `Long`** — stored as `JSONB`, where JSON has a single number type: `5L` reads back as an `Integer`. Typed fields (`long`, `Long`) are unaffected.

### AOT and native images

`PgDao` carries `@Reflected`, and `PostgresDaoInfrastructureSeed` registers it in `AOTRegistry`. The PostgreSQL JDBC driver and HikariCP carry their own native-image metadata; no build of this repository exercises it.

## Usage

The simplest wiring is the starter: add `garganttua-api-starter-postgresql` and set `postgresql.url`. To wire a domain by hand:

```java
DataSource dataSource = ...;                        // one pool for the whole application
PgSchemaRegistry registry = new PgSchemaRegistry();  // shared: lets a @Composed field reach another domain

ApiBuilder.builder()
    .domain(User.class)
        .entity().id("id").uuid("uuid").tenantId("tenantId").up()
        .dto(UserDto.class)
            .id("id").uuid("uuid").tenantId("tenantId")
            .db(new PgDao(dataSource, "users", registry, SchemaMode.CREATE))
        .up()
    .up()
    .build();
```

## Tips and best practices

- **Share one `PgSchemaRegistry` across the domains of a database.** A `@Composed` field is resolved against the target domain's table, which only that domain's DAO knows. `new PgDao(dataSource, domain)` creates a private registry: fine without compositions, not with them.
- **Share one pooled `DataSource`.** Each operation borrows a connection and returns it.
- **Use `SchemaMode.VALIDATE` where DDL goes through review.** It checks everything and changes nothing.
- **Indexes are yours**, as on MongoDB: the DAO creates primary keys and child-table keys only. Index the columns your filters and sorts use.

## Parameters

<!-- AUTO-GENERATED-PARAMETERS-START -->
<!-- AUTO-GENERATED-PARAMETERS-END -->

## License
This module is distributed under the MIT License.
