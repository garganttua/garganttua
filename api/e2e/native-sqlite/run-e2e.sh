#!/usr/bin/env bash
# Native proof of the SQLite stack, end to end:
#   1. builds the native binary (GraalVM 25, -Pnative-e2e) — skip with SKIP_BUILD=1;
#   2. runs it on a fresh SQLite database file — nothing to start, the database is the file;
#   3. drives it over HTTP (e2e_scenario.py, the scenario of the PostgreSQL and MongoDB proofs);
#   4. checks, in the file itself, the journal mode and the indexes the application created.
#
# Requirements: GraalVM 25 as JAVA_HOME, python3, and the framework installed in ~/.m2 (mvn install
# from the repository root). Run from the repository root:  api/e2e/native-sqlite/run-e2e.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
PORT_APP="${PORT_APP:-7172}"
WORK="${WORK:-$HOME/.cache/garganttua-e2e-native-sqlite}"
BINARY="$HERE/target/native-sqlite"
DB="$WORK/e2e.db"

mkdir -p "$WORK"
rm -f "$DB" "$DB-wal" "$DB-shm"

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  (cd "$ROOT" && mvn -B -ntp -Pnative-e2e -pl :garganttua-api-e2e-native-sqlite clean package -DskipTests)
fi
[ -x "$BINARY" ] || { echo "no native binary at $BINARY"; exit 1; }

cleanup() {
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null || true
}
trap cleanup EXIT

GARGANTTUA_SQLITE_PATH="$DB" GARGANTTUA_SERVER_PORT="$PORT_APP" "$BINARY" > "$WORK/app.log" 2>&1 &
APP_PID=$!
for _ in $(seq 1 60); do
  grep -q "started successfully" "$WORK/app.log" 2>/dev/null && break
  kill -0 "$APP_PID" 2>/dev/null || { echo "the binary stopped:"; tail -40 "$WORK/app.log"; exit 1; }
  sleep 1
done

status=0
python3 -I "$HERE/../e2e_scenario.py" "http://localhost:$PORT_APP" || status=$?

echo "--- database file"
python3 -I - "$DB" <<'PY' || status=$((status + 1))
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
mode = db.execute("PRAGMA journal_mode").fetchone()[0]
print("journal mode:", mode)
indexes = db.execute("SELECT name, sql FROM sqlite_master WHERE type = 'index' AND tbl_name = 'sites' "
                     "AND sql IS NOT NULL").fetchall()
for name, sql in indexes:
    print(name + ": " + sql)
unique = [sql for _, sql in indexes if sql.startswith("CREATE UNIQUE")]
if mode != "wal" or not unique:
    print("FAIL the file is not in WAL mode, or the unique index is missing")
    sys.exit(1)
PY
exit "$status"
