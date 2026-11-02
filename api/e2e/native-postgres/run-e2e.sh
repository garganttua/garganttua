#!/usr/bin/env bash
# Native proof of the PostgreSQL stack, end to end:
#   1. builds the native binary (GraalVM 25, -Pnative-e2e) — skip with SKIP_BUILD=1;
#   2. starts PostgreSQL 17 + PostGIS with TLS, behind a throwaway CA;
#   3. runs the binary against it with sslmode=verify-full (SCRAM authentication, the image default);
#   4. drives it over HTTP (e2e_scenario.py) and checks, from the server side, that the connections
#      really are encrypted and that the declared indexes exist.
#
# Requirements: GraalVM 25 as JAVA_HOME, docker (DOCKER="flatpak-spawn --host docker" from a sandbox),
# openssl, python3, and the framework installed in ~/.m2 (mvn install from the repository root).
# Run from the repository root:  api/e2e/native-postgres/run-e2e.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
DOCKER="${DOCKER:-docker}"
PORT_DB="${PORT_DB:-55433}"
PORT_APP="${PORT_APP:-7171}"
WORK="${WORK:-$HOME/.cache/garganttua-e2e-native-postgres}"
CONTAINER="garganttua-e2e-postgis-tls"
BINARY="$HERE/target/native-postgres"

mkdir -p "$WORK"

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  (cd "$ROOT" && mvn -B -ntp -Pnative-e2e -pl :garganttua-api-e2e-native-postgres clean package -DskipTests)
fi
[ -x "$BINARY" ] || { echo "no native binary at $BINARY"; exit 1; }

# A CA of our own, and a server certificate for localhost signed by it.
cd "$WORK"
if [ ! -f server.crt ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=garganttua-e2e-ca" -keyout ca.key -out ca.crt
  openssl req -newkey rsa:2048 -nodes -subj "/CN=localhost" -keyout server.key -out server.csr
  printf 'subjectAltName=DNS:localhost,IP:127.0.0.1\n' > san.ext
  openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 30 -extfile san.ext -out server.crt
fi

cleanup() {
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null || true
  $DOCKER rm -f "$CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

$DOCKER rm -f "$CONTAINER" >/dev/null 2>&1 || true
# The key must belong to the server's user with mode 0600: copied inside the container, not mounted as is.
$DOCKER run -d --name "$CONTAINER" -e POSTGRES_PASSWORD=gg -e POSTGRES_DB=e2e -p "127.0.0.1:$PORT_DB:5432" \
  -v "$WORK:/certs:ro" --entrypoint bash postgis/postgis:17-3.5 -c \
  'cp /certs/server.crt /certs/server.key /tmp/ && chown postgres /tmp/server.* && chmod 600 /tmp/server.key && exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/tmp/server.crt -c ssl_key_file=/tmp/server.key' >/dev/null
# Over TCP: during its first start the image runs a temporary server on the Unix socket only, and
# pg_isready on the socket answers yes before the real server is up.
until $DOCKER exec "$CONTAINER" pg_isready -h 127.0.0.1 -U postgres -d e2e >/dev/null 2>&1; do sleep 1; done

GARGANTTUA_POSTGRESQL_URL="jdbc:postgresql://localhost:$PORT_DB/e2e?sslmode=verify-full&sslrootcert=$WORK/ca.crt" \
GARGANTTUA_POSTGRESQL_USER=postgres GARGANTTUA_POSTGRESQL_PASSWORD=gg GARGANTTUA_SERVER_PORT="$PORT_APP" \
  "$BINARY" > "$WORK/app.log" 2>&1 &
APP_PID=$!
for _ in $(seq 1 60); do
  grep -q "started successfully" "$WORK/app.log" 2>/dev/null && break
  kill -0 "$APP_PID" 2>/dev/null || { echo "the binary stopped:"; tail -40 "$WORK/app.log"; exit 1; }
  sleep 1
done

status=0
python3 -I "$HERE/e2e_scenario.py" "http://localhost:$PORT_APP" || status=$?

echo "--- server side"
encrypted=$($DOCKER exec "$CONTAINER" psql -U postgres -d e2e -tAc \
  "SELECT count(*) FROM pg_stat_ssl s JOIN pg_stat_activity a USING (pid) WHERE a.datname = 'e2e' AND a.backend_type = 'client backend' AND a.pid <> pg_backend_pid() AND s.ssl")
plain=$($DOCKER exec "$CONTAINER" psql -U postgres -d e2e -tAc \
  "SELECT count(*) FROM pg_stat_ssl s JOIN pg_stat_activity a USING (pid) WHERE a.datname = 'e2e' AND a.backend_type = 'client backend' AND a.pid <> pg_backend_pid() AND NOT s.ssl")
echo "application connections over TLS: $encrypted, in clear: $plain"
[ "$encrypted" -gt 0 ] && [ "$plain" -eq 0 ] || { echo "FAIL not every connection is encrypted"; status=$((status + 1)); }
$DOCKER exec "$CONTAINER" psql -U postgres -d e2e -tAc \
  "SELECT indexname || ': ' || indexdef FROM pg_indexes WHERE tablename = 'sites' AND indexname <> 'sites_pkey'"
exit "$status"
