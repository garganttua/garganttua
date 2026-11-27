# Native proof — SQLite stack

## Description

Not a library, never published: the proof that the SQLite path survives GraalVM `native-image`. A
pure-AOT application on [`starter-aot-sqlite-javalin`](../../starters/starter-aot-sqlite-javalin) —
one entity holding every shape the DAO maps differently (a unique code, a flattened POJO, a list, an
untyped value, a GeoJSON point) — compiled to a native binary and driven over HTTP against an embedded
SQLite database file.

What native-image must carry here, and what this proof exercises: the SQLite library embedded in
`sqlite-jdbc` (loaded through its own GraalVM feature), HikariCP, and the Java functions the DAO
registers on every connection — called back from the native library for the BSON order of a sort,
`$regex` and `$near`.

It is built only with the `native-e2e` profile, never by the default build.

## Run it

```bash
mvn install                                   # the framework, into ~/.m2
api/e2e/native-sqlite/run-e2e.sh              # GraalVM 25 as JAVA_HOME, python3
```

`SKIP_BUILD=1` reuses a binary already built. No container, no server: the database is a file in
`~/.cache/garganttua-e2e-native-sqlite`, recreated on every run.

The script builds the binary, runs it, plays [`e2e_scenario.py`](../e2e_scenario.py), shared with the
PostgreSQL and MongoDB proofs — creation, a duplicate answering 409, every shape read back unchanged,
`$regex`, `$geoWithin`, `$near` — then opens the file to check that it is in WAL mode and lists the
indexes the application created. The same scenario runs against the JVM:
`python3 api/e2e/e2e_scenario.py http://localhost:7172`.

## License

This module is distributed under the MIT License.
