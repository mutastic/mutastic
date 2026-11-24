#!/bin/sh
# Tests scripts/central.sh against a stand-in for the Central Portal's Publisher API and repo1, a
# Python HTTP server on localhost, through each state a deployment can be in and each way a call
# can fail. Nothing reaches Central. Needs python3, curl and jq; takes a few seconds.
#
#   sh scripts/test-central.sh
set -u
cd "$(dirname "$0")/.." || exit 2

tmp=$(mktemp -d)
server=
cleanup() {
  if [ -n "$server" ]; then
    kill "$server" 2> /dev/null
    wait "$server" 2> /dev/null
  fi
  rm -rf "$tmp"
}
trap cleanup EXIT

# The stand-in. Each case writes $tmp/case.json, then loads it:
#   status   the responses to the status call in turn, [code, body]; the last repeats
#   publish, drop   the code for POST and DELETE /api/v1/publisher/deployment/<id>
#   repo1    per POM path, the codes for HEAD in turn; the last repeats
# Every request is written to $tmp/requests as "METHOD path", and the token checked.
cat > "$tmp/stub.py" << 'EOF'
import base64, http.server, json, sys

case_file, log_file, port_file = sys.argv[1:4]
expected = "Bearer " + base64.b64encode(b"stub-user:stub-password-long-enough-to-wrap-in-base64-0123456789abcdef").decode()
state = {}


def next_of(key, items):
    i = state.setdefault(key, 0)
    state[key] = i + 1
    return items[min(i, len(items) - 1)]


class Stub(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def answer(self, code, body=b""):
        self.send_response(code)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def handle_any(self):
        global case
        if self.path == "/__load":
            case = json.load(open(case_file))
            state.clear()
            return self.answer(200)
        with open(log_file, "a") as log:
            log.write(f"{self.command} {self.path}\n")
        if self.path.startswith("/repo1/"):
            codes = case.get("repo1", {}).get(self.path[len("/repo1/"):], [404])
            return self.answer(next_of(self.path, codes))
        if self.headers.get("Authorization") != expected:
            return self.answer(401, b'{"error":"bad token"}')
        if self.command == "POST" and self.path.startswith("/api/v1/publisher/status?id="):
            code, body = next_of("status", case["status"])
            return self.answer(code, json.dumps(body).encode() if not isinstance(body, str) else body.encode())
        if self.path.startswith("/api/v1/publisher/deployment/"):
            key = {"POST": "publish", "DELETE": "drop"}.get(self.command)
            if key:
                return self.answer(case.get(key, 204))
        self.answer(400, b"not a call the stand-in knows")

    do_GET = do_POST = do_DELETE = do_HEAD = handle_any


case = {}
httpd = http.server.HTTPServer(("127.0.0.1", 0), Stub)
open(port_file, "w").write(str(httpd.server_port))
httpd.serve_forever()
EOF

python3 -I "$tmp/stub.py" "$tmp/case.json" "$tmp/requests" "$tmp/port" &
server=$!
for _ in 1 2 3 4 5 6 7 8 9 10; do [ -s "$tmp/port" ] && break; sleep 0.2; done
[ -s "$tmp/port" ] || { echo "test-central: the stand-in didn't start" >&2; exit 1; }
base=http://127.0.0.1:$(cat "$tmp/port")

export CENTRAL_URL="$base" REPO1_URL="$base/repo1" CENTRAL_POLL_SECONDS=0
export MAVEN_CENTRAL_USERNAME=stub-user
export MAVEN_CENTRAL_PASSWORD=stub-password-long-enough-to-wrap-in-base64-0123456789abcdef
id=28570f16-da32-4c14-bd2e-c1acc0782365
failures=0
passed=0

# load <case json>
load() {
  printf '%s\n' "$1" > "$tmp/case.json"
  : > "$tmp/requests"
  curl -fsS -o /dev/null "$base/__load"
}

# expect <name> <exit status> <text the output has, or ""> <command...>
expect() {
  name=$1 status=$2 text=$3
  shift 3
  "$@" > "$tmp/out" 2>&1
  actual=$?
  if [ "$actual" -ne "$status" ]; then
    echo "FAIL $name: exited $actual, not $status"
    sed 's/^/    /' "$tmp/out"
    failures=$((failures + 1))
  elif [ -n "$text" ] && ! grep -qF -- "$text" "$tmp/out"; then
    echo "FAIL $name: the output doesn't say \"$text\""
    sed 's/^/    /' "$tmp/out"
    failures=$((failures + 1))
  else
    echo "ok   $name"
    passed=$((passed + 1))
  fi
}

# requested <name> <count> <"METHOD path" prefix>: how many such requests the case made.
requested() {
  count=$(grep -c "^$3" "$tmp/requests")
  if [ "$count" -ne "$2" ]; then
    echo "FAIL $1: $count requests \"$3\", not $2"
    sed 's/^/    /' "$tmp/requests"
    failures=$((failures + 1))
  else
    echo "ok   $1"
    passed=$((passed + 1))
  fi
}

central() {
  sh scripts/central.sh "$@"
}
st() { printf '[200, {"deploymentId": "%s", "deploymentName": "mutastic", "deploymentState": "%s"%s}]' "$id" "$1" "${2:-}"; }
purls=', "purls": ["pkg:maven/io.mutastic/mutastic-api@1.4.0", "pkg:maven/io.mutastic/mutastic-cli@1.4.0"]'

load "{\"status\": [$(st VALIDATED "$purls")]}"
expect "status prints Central's JSON" 0 '"deploymentState": "VALIDATED"' central status "$id"
requested "status is a POST with the id" 1 "POST /api/v1/publisher/status?id=$id"

load "{\"status\": [$(st PENDING), $(st VALIDATING), $(st VALIDATED)]}"
expect "wait: pending, validating, validated" 0 "deployment $id is VALIDATED" central wait "$id" VALIDATED 1
requested "wait polls until it's validated" 3 "POST /api/v1/publisher/status"

load "{\"status\": [$(st PUBLISHED)]}"
expect "wait for validated: already published" 0 "is PUBLISHED" central wait "$id" VALIDATED 1

load "{\"status\": [$(st VALIDATING), $(st FAILED ', "errors": {"pkg:maven/io.mutastic/mutastic-api@1.4.0": ["Missing signature for file: mutastic-api-1.4.0.jar"]}')]}"
expect "wait: failed validation says why" 1 "Missing signature for file: mutastic-api-1.4.0.jar" central wait "$id" VALIDATED 1

load "{\"status\": [$(st VALIDATING)]}"
expect "wait: gives up when time's up" 1 "still VALIDATING after 0 minutes" central wait "$id" VALIDATED 0

load "{\"status\": [$(st SOMETHING_NEW)]}"
expect "wait: a state it doesn't know" 1 "a state this script doesn't know" central wait "$id" VALIDATED 1

load "{\"status\": [$(st PUBLISHING), $(st PUBLISHED)]}"
expect "wait: publishing, published" 0 "is PUBLISHED" central wait "$id" PUBLISHED 1

load "{\"status\": [$(st VALIDATED)]}"
expect "wait: only validated or published" 2 "" central wait "$id" PENDING 1

load "{\"status\": [$(st VALIDATED)], \"publish\": 204}"
expect "publish: a validated deployment" 0 "is being published" central publish "$id"
requested "publish POSTs the deployment" 1 "POST /api/v1/publisher/deployment/$id"

load "{\"status\": [$(st PUBLISHED)]}"
expect "publish: once only" 0 "already PUBLISHED" central publish "$id"
requested "publish: nothing sent when it's published" 0 "POST /api/v1/publisher/deployment/"

load "{\"status\": [$(st FAILED)]}"
expect "publish: refuses one that failed" 1 "only a VALIDATED deployment can be published" central publish "$id"
requested "publish: nothing sent when it failed" 0 "POST /api/v1/publisher/deployment/"

load "{\"status\": [$(st VALIDATED)], \"publish\": 400}"
expect "publish: Central refuses" 1 "publishing deployment $id: Central answered HTTP 400" central publish "$id"

load "{\"status\": [$(st VALIDATED)], \"drop\": 204}"
expect "drop: a validated deployment" 0 "dropped deployment $id, which was VALIDATED" central drop "$id"
requested "drop DELETEs the deployment" 1 "DELETE /api/v1/publisher/deployment/$id"

load "{\"status\": [$(st FAILED)], \"drop\": 204}"
expect "drop: a failed deployment" 0 "which was FAILED" central drop "$id"

load "{\"status\": [$(st PUBLISHED)]}"
expect "drop: never a published one" 1 "fix forward" central drop "$id"
requested "drop: nothing sent when it's published" 0 "DELETE"

load "{\"status\": [$(st PUBLISHING)]}"
expect "drop: never one being published" 1 "fix forward" central drop "$id"

load "{\"status\": [$(st VALIDATING)]}"
expect "drop: waits out validation" 1 "only a VALIDATED or FAILED deployment can be dropped" central drop "$id"

load "{\"status\": [[404, \"\"]]}"
expect "a deployment Central doesn't have" 1 "Central has no such deployment (HTTP 404)" central status "$id"

load "{\"status\": [[500, \"\"], [502, \"\"], $(st VALIDATED)]}"
expect "a server error is tried again" 0 '"VALIDATED"' central status "$id"
requested "three tries in all" 3 "POST /api/v1/publisher/status"

load "{\"status\": [[503, \"\"]]}"
expect "three server errors fail" 1 "Central answered HTTP 503" central status "$id"

load '{"status": [[200, "{\"unexpected\": true}"]]}'
expect "a status without a state" 1 "has no deploymentState" central status "$id"

load "{\"status\": [$(st VALIDATED)]}"
expect "the wrong token" 1 "Central refused the token (HTTP 401)" \
  env MAVEN_CENTRAL_PASSWORD=wrong sh scripts/central.sh status "$id"
expect "no token" 1 "MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD must be set" \
  env MAVEN_CENTRAL_PASSWORD= sh scripts/central.sh status "$id"
expect "an id that isn't one" 1 "isn't a deployment id" central status "../../etc"
expect "a command it doesn't have" 2 "" central promote "$id"

api=io/mutastic/mutastic-api/1.4.0/mutastic-api-1.4.0.pom
cli=io/mutastic/mutastic-cli/1.4.0/mutastic-cli-1.4.0.pom
load "{\"status\": [$(st PUBLISHED "$purls")], \"repo1\": {\"$api\": [200], \"$cli\": [404, 404, 200]}}"
expect "served: waits for every component's POM" 0 "repo1 serves all 2 of deployment $id's components" central served "$id" 1
requested "served: asks again for what's missing" 3 "HEAD /repo1/$cli"

load "{\"status\": [$(st PUBLISHED "$purls")], \"repo1\": {\"$api\": [200]}}"
expect "served: names what repo1 doesn't have" 1 "$base/repo1/$cli" central served "$id" 0

load "{\"status\": [$(st PUBLISHING "$purls")]}"
expect "served: only once it's published" 1 "is PUBLISHING, not PUBLISHED" central served "$id" 1

echo "test-central: $passed passed, $failures failed"
[ "$failures" -eq 0 ]
