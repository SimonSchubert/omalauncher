#!/usr/bin/env bash
# Runs the WebSocket client (app/src/main/kotlin/dev/omalauncher/WebSocket.kt) through the Autobahn
# TestSuite's fuzzing server in Docker and lists the cases it didn't pass. The permessage-deflate cases
# (12.*, 13.*) are left out: the client doesn't offer compression. The full report ends up in
# build/autobahn/clients/index.html.
#
#   scripts/autobahn.sh [case pattern, default *]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/autobahn"
PORT=9001
NAME=omalauncher-autobahn
rm -rf "$OUT"
mkdir -p "$OUT"
cat >"$OUT/fuzzingserver.json" <<JSON
{
  "url": "ws://0.0.0.0:$PORT",
  "outdir": "/autobahn/clients",
  "cases": ["${1:-*}"],
  "exclude-cases": ["12.*", "13.*"],
  "exclude-agent-cases": {}
}
JSON

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --rm --name "$NAME" -p "127.0.0.1:$PORT:$PORT" -v "$OUT:/autobahn" \
  crossbario/autobahn-testsuite wstest -m fuzzingserver -s /autobahn/fuzzingserver.json >/dev/null
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT
for _ in $(seq 60); do
  curl -s -o /dev/null "http://127.0.0.1:$PORT" && break
  sleep 1
done

AUTOBAHN="ws://127.0.0.1:$PORT" "$ROOT/gradlew" -p "$ROOT" :app:testDebugUnitTest \
  --tests dev.omalauncher.WebSocketAutobahnTest --rerun -q

python3 - "$OUT/clients/index.json" <<'PY'
import json, sys
report = json.load(open(sys.argv[1]))
results = next(iter(report.values()))
bad = {c: r for c, r in results.items() if r["behavior"] not in ("OK", "INFORMATIONAL", "NON-STRICT")
       or r["behaviorClose"] not in ("OK", "INFORMATIONAL")}
key = lambda c: [int(p) for p in c.split(".")]
for c in sorted(bad, key=key):
    print(f"{c}: {bad[c]['behavior']} / close {bad[c]['behaviorClose']}")
print(f"{len(results) - len(bad)} of {len(results)} cases passed")
sys.exit(1 if bad else 0)
PY
