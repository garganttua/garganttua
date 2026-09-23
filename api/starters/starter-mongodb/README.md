# Garganttua API Starter — MongoDB

## Description

The **MongoDB starter** is a persistence add-on. Drop it on the classpath alongside the [bootstrap starter](../garganttua-api-starter-bootstrap) and every annotation-scanned domain becomes persistable in MongoDB **with no DSL**: a `MongoAutoConfiguration` reads the connection settings from your `application.yaml`, opens a `MongoClient`, and registers a default DAO that yields a `MongoDao` per domain (the collection name is the plural domain name, e.g. `User` → `users`).

A domain whose DTO declares an explicit `.db(...)` keeps it — the default DAO is only a fallback.

**Key features:**
- **Zero-DSL persistence** — annotate `@Entity`/`@Dto`, set `mongodb.uri`/`mongodb.database`, and CRUD persists to MongoDB.
- **One client, many collections** — a single `MongoClient` serves every domain; each domain maps to its own collection.
- **Lifecycle-managed** — the `MongoClient` is registered as a closeable resource and shut down with the application.
- **Declared indexes, created at startup** — a `@EntityIndexed` field gets its MongoDB index without a manual step; `mongodb.index.auto` says who creates it and what a failure costs.
- **Composable** — combine with `garganttua-api-starter-javalin` to expose the persisted domains over HTTP (see `garganttua-api-starter-jvm-mongo-javalin`).

## Installation

<!-- AUTO-GENERATED-START -->
### Installation with Maven
```xml
<dependency>
    <groupId>com.garganttua</groupId>
    <artifactId>garganttua-api-starter-mongodb</artifactId>
    <version>3.0.0-ALPHA23</version>
</dependency>
```

### Actual version
3.0.0-ALPHA23

### Dependencies
 - `com.garganttua:garganttua-api-starter-bootstrap`
 - `com.garganttua:garganttua-api-dao-mongodb`
 - `com.garganttua.core:garganttua-starter-runtime:test`
 - `org.junit.jupiter:junit-jupiter-engine:test`
 - `org.mockito:mockito-core:test`
 - `org.mockito:mockito-junit-jupiter:test`

<!-- AUTO-GENERATED-END -->

## Usage

```java
public final class MyApp {
    public static void main(String[] args) {
        GarganttuaApplication.run(MyApp.class, args);
    }
}
```

`application.yaml`:

```yaml
api:
  multiTenant: false
  packages: com.myapp
mongodb:
  uri: mongodb://localhost:27017
  database: myapp
```

That is all: the auto-configuration wires a `MongoDao` onto every domain that did not declare its own DAO.

## Configuration keys

| Key | Required | Effect |
|---|---|---|
| `mongodb.uri` | yes | MongoDB connection string passed to `MongoClients.create(...)` |
| `mongodb.database` | yes | database name; each domain maps to a collection named after the domain |
| `mongodb.index.auto` | no | who creates the indexes a domain declares — `create` (default), `none`, `strict` |

Both required keys are overridable by environment variables: `GARGANTTUA_MONGODB_URI`, `GARGANTTUA_MONGODB_DATABASE`. A missing key fails fast at startup with a pointed message.

### `mongodb.index.auto` — the declared indexes

The counterpart of the PostgreSQL starter's `postgresql.schema.auto`. A domain declares an index with `@EntityIndexed` on the entity field, or with `entity().index(...)` on the DSL; this setting says who creates it.

| Value | Effect |
|---|---|
| `create` (default, also `true`) | Create the declared indexes that are missing. Never drop, rename or alter one that exists. An index that cannot be created is a `WARN` naming the gap, and **the application starts**. |
| `none` (also `false`) | Send no index command at all, and read nothing. For a database whose indexes are owned by a separate, reviewed process. |
| `strict` | As `create`, except that an index that cannot be created **stops the application**, with the reason. For an environment where a declared uniqueness the database does not hold must never go unnoticed. |

A value naming none of these is refused at startup rather than silently downgraded: a misspelt `strict` quietly becoming `create` would be exactly the silent gap the setting exists to close.

**Why `create` starts anyway.** A unique index is the one piece of DDL that fails on *data* rather than on permissions: a collection that already holds duplicates refuses it — and that is precisely the database this feature exists for. The PostgreSQL side refuses to start on a schema failure because a missing table means no query runs at all; a missing index means every query still runs, just without a constraint the database never held in the first place. So the failure is reported, loudly and actionably, rather than taking a running service down:

```
WARN  Domain 'users': unique index 'gg_email_tenant_standard_unique' on field 'email' could NOT be
      created — the collection 'users' already holds duplicate values for it. The uniqueness is
      therefore NOT enforced by the database. List the offending documents with:
      db.getCollection("users").aggregate([{$match:{"email":{$ne:null}}},{$group:{_id:{"tenantId":
      "$tenantId","email":"$email"},count:{$sum:1},ids:{$push:"$_id"}}},{$match:{count:{$gt:1}}}])
      — then merge or delete them and restart.
```

Paste the aggregation, merge or delete the duplicates, restart; the index is then created. Use `strict` once the database is known to be clean.

**A domain that declares no index costs nothing**: not one command is sent at startup, so an application that never asked for an index starts exactly as it did before this existed.

## Notes

- The collection name is the **plural, lower-case domain name** (`Order` → `orders`).
- The DAO is created once per domain at build time; document read-back relies on the framework handing each DAO its domain definition at startup, so no extra wiring is needed.
- Index creation happens at that same moment, from the same domain definition. See [the DAO's README](../../dao/dao-mongodb) for what each declaration becomes in MongoDB.

## License

This module is distributed under the MIT License.
