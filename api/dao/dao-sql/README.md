# Garganttua API SQL DAO core

## Description

`garganttua-api-dao-sql` is the **relational core shared by the SQL DAOs** of the Garganttua API framework — [PostgreSQL](../dao-postgresql) and [SQLite](../dao-sqlite). It holds everything that does not depend on the database: how a DTO becomes tables, how MongoDB's filters, sorts, pages and projections become SQL with MongoDB's semantics, how an entity is written and read back. Each database contributes only an `ISqlDialect` — what it spells differently — and a DAO that prepares its schema. It carries no JDBC driver.

**Key Features:**
- **One mapping** — `SqlSchemaModel` derives the tables of a domain from its DTO once (columns, flattened POJOs, child tables at any depth, presence columns, 63-byte names); every SQL DAO builds the same tables
- **One translation of MongoDB's semantics** — BSON type bracketing and order, array "any element", absent vs null, exact number comparison, `$in`, `$regex`, `$text`, the geo operators, sorts on structures, pages with the uuid as a final key
- **One read and write path** — `SqlWriter` (upsert, child tables level by level, list digests when the dialect offers them), `SqlReader` (one statement, collections aggregated by `SqlChildAggregate`), `SqlRowPlan`
- **`AbstractSqlDao`** — the `IDao` orchestration: registration, find, page, save, delete, count, transactions
- **Shared planning** — `SqlIndexPlanner` (what a declared index covers) and `SqlGeoFilters` (what MongoDB demands of a geo filter)

## Installation

<!-- AUTO-GENERATED-START -->
### Installation with Maven
```xml
<dependency>
    <groupId>com.garganttua</groupId>
    <artifactId>garganttua-api-dao-sql</artifactId>
    <version>3.0.0-ALPHA38</version>
</dependency>
```

### Actual version
3.0.0-ALPHA38

### Dependencies
 - `com.garganttua:garganttua-api-commons`
 - `com.garganttua:garganttua-api-binding-geojson`
 - `com.garganttua:garganttua-api-binding-jackson`
 - `com.garganttua.core:garganttua-crypto`
 - `com.garganttua.core:garganttua-aot-reflection`
 - `com.garganttua.core:garganttua-aot-commons`
 - `org.junit.jupiter:junit-jupiter-engine:test`
 - `com.garganttua.core:garganttua-bootstrap:test`
 - `com.garganttua.core:garganttua-runtime-reflection:test`
 - `com.garganttua.core:garganttua-reflections:test`

<!-- AUTO-GENERATED-END -->

## Core Concepts

### The dialect

`ISqlDialect` is the seam: about forty operations a database spells its own way — binding JSON and geometries, the exact-number parameter, NaN, the binary collation, reading into JSON, the BSON order of a whole structure (rows and arrays on PostgreSQL, text keys on SQLite), regular expressions, text search, geography, the aggregation of child rows, lists of values, unique violations, the storage and decoding of each type, the DDL of a child table, how a transaction locks. A dialect is stateless; each `SqlTable` carries its own, so every part of the core reads it from the model.

### Optional capabilities

`ISqlListDigests` — a digest of each collection, stored on the main row and kept honest by triggers, so that a write leaves unchanged collections untouched. PostgreSQL offers it; without it (SQLite) every write rewrites the collections of the entity, which is correct, only slower.

### Proving a dialect

The parity scenarios of the PostgreSQL DAO (published as its `tests` jar) run any dialect against a real MongoDB through a `ParityEngine`: `dao-sqlite` runs all of them on SQLite.

## Usage

Not used directly: depend on [`garganttua-api-dao-postgresql`](../dao-postgresql) or [`garganttua-api-dao-sqlite`](../dao-sqlite).

## Tips and best practices

- A new SQL database is a new `ISqlDialect` and an `AbstractSqlDao` subclass whose `prepare` creates the tables — not a new mapping. Run the parity suite on it before anything else.

## Parameters

<!-- AUTO-GENERATED-PARAMETERS-START -->
<!-- AUTO-GENERATED-PARAMETERS-END -->

## License
This module is distributed under the MIT License.
