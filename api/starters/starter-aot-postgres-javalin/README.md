# Garganttua API Starter — AOT (PostgreSQL + Javalin)

## Description

The PostgreSQL twin of [`garganttua-api-starter-aot-mongo-javalin`](../starter-aot-mongo-javalin): the bootstrap runner, the [PostgreSQL starter](../starter-postgresql) (one HikariCP pool, one `PgDao` per domain) and the Javalin transport, with the **AOT reflection providers only** — no runtime reflection stack, no classpath scan. It targets **GraalVM native-image**.

## Installation

<!-- AUTO-GENERATED-START -->
### Installation with Maven
```xml
<dependency>
    <groupId>com.garganttua</groupId>
    <artifactId>garganttua-api-starter-aot-postgres-javalin</artifactId>
    <version>3.0.0-ALPHA31</version>
</dependency>
```

### Actual version
3.0.0-ALPHA31

### Dependencies
 - `com.garganttua.core:garganttua-aot-reflection`
 - `com.garganttua.core:garganttua-aot-annotation-scanner`
 - `com.garganttua:garganttua-api-starter-bootstrap`
 - `com.garganttua:garganttua-api-starter-postgresql`
 - `com.garganttua:garganttua-api-starter-javalin`

<!-- AUTO-GENERATED-END -->

## Usage

```java
public final class MyApp {
    public static void main(String[] args) {
        GarganttuaApplication.runAndWait(MyApp.class, args);
    }
}
```

For a native image, add `com.garganttua.core:garganttua-starter-native` (type `pom`) and the GraalVM `native-maven-plugin`; nothing else is needed for the PostgreSQL path — the JDBC driver, HikariCP and the DAO's decoders need no hand-written `reflect-config`, and `application.yaml` is embedded by `garganttua-api-commons`. Exclude `ch.qos.logback:logback-classic` and `com.garganttua.core:garganttua-starter-runtime` to stay pure AOT.

## Proved native

[`api/e2e/native-postgres`](../../e2e/native-postgres) builds an application on this starter with native-image and drives it over HTTP against PostgreSQL 17 + PostGIS, through TLS (`sslmode=verify-full`, SCRAM): a flattened POJO, a list (child table), an untyped value (JSONB), a GeoJSON geometry, `$regex`, `$geoWithin`, `$near`, and a unique index answering 409. Run it with `api/e2e/native-postgres/run-e2e.sh`.

## Configuration keys

`api.*`, `server.port`, and the PostgreSQL starter's keys: `postgresql.url` (required; TLS through the URL, e.g. `?sslmode=verify-full&sslrootcert=/etc/ssl/ca.crt`), `postgresql.user`, `postgresql.password`, `postgresql.pool.size`, `postgresql.schema.auto`, `postgresql.index.auto`. All environment-overridable (`GARGANTTUA_…`).

## License

This module is distributed under the MIT License.
