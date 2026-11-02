# Native proof — MongoDB stack

## Description

Not a library, never published: the proof that the MongoDB path survives GraalVM `native-image`. The
application of the [PostgreSQL proof](../native-postgres) — one entity holding a unique code, an embedded
POJO, a list, an untyped value and a GeoJSON point — on
[`starter-aot-mongo-javalin`](../../starters/starter-aot-mongo-javalin), compiled to a native binary and
driven over HTTP against MongoDB 4.4 and 7.0, the two versions palliad runs.

It is built only with the `native-e2e` profile, never by the default build.

## Run it

```bash
mvn install                                         # the framework, into ~/.m2
api/e2e/native-mongo/run-e2e.sh                     # GraalVM 25 as JAVA_HOME, docker, python3
MONGO_IMAGE=mongo:4.4 SKIP_BUILD=1 api/e2e/native-mongo/run-e2e.sh
```

From a sandbox without a local docker: `DOCKER="flatpak-spawn --host docker"`.

The script starts MongoDB, runs the binary against it and plays [`e2e_scenario.py`](../e2e_scenario.py) —
the PostgreSQL proof's checks, plus a paginated `$near`, which MongoDB refuses to count as such — then
prints, from the server side, the indexes the application declared and a point as it is stored: GeoJSON,
which the `2dsphere` index needs.

## License

This module is distributed under the MIT License.
