# Native proof — PostgreSQL stack

## Description

Not a library, never published: the proof that the PostgreSQL path survives GraalVM `native-image`. A
pure-AOT application on [`starter-aot-postgres-javalin`](../../starters/starter-aot-postgres-javalin) —
one entity holding every shape the DAO maps differently (a unique code, a flattened POJO, a list, an
untyped value, a GeoJSON point) — compiled to a native binary and driven over HTTP against PostgreSQL 17
+ PostGIS, through TLS (`sslmode=verify-full`, SCRAM).

It is built only with the `native-e2e` profile, never by the default build.

## Run it

```bash
mvn install                                   # the framework, into ~/.m2
api/e2e/native-postgres/run-e2e.sh            # GraalVM 25 as JAVA_HOME, docker, openssl, python3
```

From a sandbox without a local docker: `DOCKER="flatpak-spawn --host docker" api/e2e/native-postgres/run-e2e.sh`.
`SKIP_BUILD=1` reuses a binary already built.

The script builds the binary, starts `postgis/postgis:17-3.5` with TLS behind a throwaway CA, runs the
binary against it, plays [`e2e_scenario.py`](e2e_scenario.py) — creation, a duplicate answering 409,
every shape read back unchanged, `$regex`, `$geoWithin`, `$near` — then checks from the server side
(`pg_stat_ssl`) that every connection of the application is encrypted, and lists the indexes it created.
The same scenario runs against the JVM: `python3 e2e_scenario.py http://localhost:7171`.

## What it found

The first native runs failed where the JVM passed, on gaps of the framework itself, now fixed: the
configuration file was not embedded (`api-commons` ships it as a native resource), the response
envelopes and `Page` serialised to `{}`, the GeoJSON types could not be read (`binding-javalin`,
`api-commons` and `binding-jackson` ship their reflection metadata), and the read order was lost by
`Repository` — on the JVM too, hidden by chance.

## License

This module is distributed under the MIT License.
