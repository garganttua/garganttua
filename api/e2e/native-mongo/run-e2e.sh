#!/usr/bin/env bash
# Native proof of the MongoDB stack, end to end:
#   1. builds the native binary (GraalVM 25, -Pnative-e2e) — skip with SKIP_BUILD=1;
#   2. starts MongoDB (MONGO_IMAGE, mongo:7.0 by default; palliad also runs mongo:4.4);
#   3. runs the binary against it and drives it over HTTP (../e2e_scenario.py, the PostgreSQL
#      proof's scenario);
#   4. lists, from the server side, the indexes the application declared — the 2dsphere among them.
#
# Requirements: GraalVM 25 as JAVA_HOME, docker (DOCKER="flatpak-spawn --host docker" from a sandbox),
# python3, and the framework installed in ~/.m2 (mvn install from the repository root).
# Run from the repository root:  api/e2e/native-mongo/run-e2e.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
DOCKER="${DOCKER:-docker}"
MONGO_IMAGE="${MONGO_IMAGE:-mongo:7.0}"
PORT_DB="${PORT_DB:-27117}"
PORT_APP="${PORT_APP:-7172}"
WORK="${WORK:-$HOME/.cache/garganttua-e2e-native-mongo}"
CONTAINER="garganttua-e2e-mongo"
BINARY="$HERE/target/native-mongo"

mkdir -p "$WORK"

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  (cd "$ROOT" && mvn -B -ntp -Pnative-e2e -pl :garganttua-api-e2e-native-mongo clean package -DskipTests)
fi
[ -x "$BINARY" ] || { echo "no native binary at $BINARY"; exit 1; }

cleanup() {
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null || true
  $DOCKER rm -f "$CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

$DOCKER rm -f "$CONTAINER" >/dev/null 2>&1 || true
$DOCKER run -d --name "$CONTAINER" -p "127.0.0.1:$PORT_DB:27017" "$MONGO_IMAGE" >/dev/null
# mongo:4.4 ships the legacy 'mongo' shell, later images 'mongosh'.
SHELL_CMD=mongosh
$DOCKER exec "$CONTAINER" which mongosh >/dev/null 2>&1 || SHELL_CMD=mongo
until $DOCKER exec "$CONTAINER" $SHELL_CMD --quiet --eval 'db.runCommand({ping: 1}).ok' >/dev/null 2>&1; do sleep 1; done

GARGANTTUA_MONGODB_URI="mongodb://localhost:$PORT_DB" GARGANTTUA_MONGODB_DATABASE=e2e \
GARGANTTUA_SERVER_PORT="$PORT_APP" "$BINARY" > "$WORK/app.log" 2>&1 &
APP_PID=$!
for _ in $(seq 1 60); do
  grep -q "started successfully" "$WORK/app.log" 2>/dev/null && break
  kill -0 "$APP_PID" 2>/dev/null || { echo "the binary stopped:"; tail -40 "$WORK/app.log"; exit 1; }
  sleep 1
done

status=0
python3 -I "$HERE/../e2e_scenario.py" "http://localhost:$PORT_APP" || status=$?

echo "--- server side ($MONGO_IMAGE)"
$DOCKER exec "$CONTAINER" $SHELL_CMD e2e --quiet --eval \
  'db.sites.getIndexes().forEach(i => print(i.name + ": " + JSON.stringify(i.key)))'
$DOCKER exec "$CONTAINER" $SHELL_CMD e2e --quiet --eval \
  'print("stored location: " + JSON.stringify(db.sites.findOne({code: "PAR"}).location))'
exit "$status"
