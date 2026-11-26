#!/bin/sh
# A Maven Central deployment, through the Central Portal's Publisher API
# (https://central.sonatype.org/publish/publish-portal-api/), so a release's flow doesn't depend
# on the build tool that uploaded it. The release workflow uploads with Gradle, as USER_MANAGED,
# then uses this to wait for validation, publish once approved, and drop what was never published.
#
#   scripts/central.sh status ID                the deployment's status, as Central's JSON
#   scripts/central.sh wait ID STATE [MINUTES]  waits for VALIDATED or PUBLISHED (default 30)
#   scripts/central.sh publish ID               publishes a VALIDATED deployment, once
#   scripts/central.sh drop ID                  drops a VALIDATED or FAILED deployment
#   scripts/central.sh served ID FILE [MINUTES] waits for repo1 to serve each of its jars
#
# served's FILE is the deployment's status as `status` printed it while VALIDATED: Central lists a
# deployment's purls then, and none once it's PUBLISHED (1.4.1's release, run 45879952796).
#
# The token is the Central Portal user token, MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD,
# joined by a colon and base64 encoded, as the API's docs say. CENTRAL_URL, REPO1_URL and
# CENTRAL_POLL_SECONDS (15) are for tests. A published deployment can't be changed or dropped:
# drop refuses one, and a failure after that is fixed forward, in the next patch.
set -eu

usage() {
  sed -n '7,11s/^# *//p' "$0" >&2
  exit 2
}

central=${CENTRAL_URL:-https://central.sonatype.com}
repo1=${REPO1_URL:-https://repo1.maven.org/maven2}
poll=${CENTRAL_POLL_SECONDS:-15}

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

say() {
  printf 'central: %s\n' "$*" >&2
}
fail() {
  say "$*"
  exit 1
}

token() {
  [ -n "${MAVEN_CENTRAL_USERNAME:-}" ] && [ -n "${MAVEN_CENTRAL_PASSWORD:-}" ] ||
    fail "MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD must be set: the Central Portal user token"
  # GNU base64 wraps at 76 characters, and a token's two halves can be longer than that. The
  # encoding isn't masked in a workflow's log as the secrets are, so it's never printed: it goes
  # to curl on stdin.
  printf '%s:%s' "$MAVEN_CENTRAL_USERNAME" "$MAVEN_CENTRAL_PASSWORD" | base64 | tr -d '\n'
}

# call METHOD PATH: the response's body in $tmp/body, its HTTP status printed. The header goes to
# curl on stdin, so the token is never in a process's arguments. A server error or a dropped
# connection is tried again, three times in all.
call() {
  bearer=$(token)
  for attempt in 1 2 3; do
    code=$(printf 'header = "Authorization: Bearer %s"\n' "$bearer" |
      curl -sS -K - -X "$1" -o "$tmp/body" -w '%{http_code}' --max-time 60 "$central$2") || code=000
    case $code in
      000 | 5??) [ "$attempt" -lt 3 ] && sleep "$poll" ;;
      *) break ;;
    esac
  done
  printf '%s' "$code"
}

# explain CODE WHAT: why a call failed, as said by the HTTP status and Central's body.
explain() {
  body=$(head -c 2000 "$tmp/body" 2> /dev/null || true)
  case $1 in
    000) fail "$2: Central didn't answer at $central" ;;
    401 | 403) fail "$2: Central refused the token (HTTP $1): check MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD, a user token from the Central Portal${body:+: $body}" ;;
    404) fail "$2: Central has no such deployment (HTTP 404)${body:+: $body}" ;;
    *) fail "$2: Central answered HTTP $1${body:+: $body}" ;;
  esac
}

# The deployment's status JSON in $tmp/status. The API takes it as a POST.
status_json() {
  code=$(call POST "/api/v1/publisher/status?id=$1")
  [ "$code" = 200 ] || explain "$code" "the status of deployment $1"
  jq -e '.deploymentState | strings' "$tmp/body" > /dev/null 2>&1 ||
    fail "the status of deployment $1 has no deploymentState: $(head -c 2000 "$tmp/body")"
  cp "$tmp/body" "$tmp/status"
}

state_of() {
  jq -r .deploymentState "$tmp/status"
}

errors_of() {
  jq -r '.errors // {} | if type == "object" then to_entries[] | "  \(.key): \(.value | if type == "array" then join("; ") else tostring end)" else "  \(tostring)" end' "$tmp/status" 2> /dev/null
}

wait_for() {
  id=$1
  wanted=$2
  minutes=${3:-30}
  case $wanted in VALIDATED | PUBLISHED) ;; *) usage ;; esac
  deadline=$(($(date +%s) + minutes * 60))
  last=
  while :; do
    status_json "$id"
    state=$(state_of)
    [ "$state" = "$last" ] || say "deployment $id is $state"
    last=$state
    case $state in
      "$wanted") return 0 ;;
      PUBLISHING | PUBLISHED)
        # Asked for VALIDATED, and it's already past it.
        [ "$wanted" = VALIDATED ] && return 0
        ;;
      FAILED) fail "deployment $id failed validation:
$(errors_of)" ;;
      PENDING | VALIDATING | VALIDATED) ;;
      *) fail "deployment $id is $state, a state this script doesn't know" ;;
    esac
    [ "$(date +%s)" -lt "$deadline" ] ||
      fail "deployment $id is still $state after $minutes minutes, not $wanted"
    sleep "$poll"
  done
}

publish() {
  status_json "$1"
  state=$(state_of)
  case $state in
    PUBLISHING | PUBLISHED)
      say "deployment $1 is already $state: nothing to do"
      return 0
      ;;
    VALIDATED) ;;
    *) fail "deployment $1 is $state: only a VALIDATED deployment can be published" ;;
  esac
  code=$(call POST "/api/v1/publisher/deployment/$1")
  case $code in 2??) ;; *) explain "$code" "publishing deployment $1" ;; esac
  say "deployment $1 is being published"
}

drop() {
  status_json "$1"
  state=$(state_of)
  case $state in
    PUBLISHING | PUBLISHED) fail "deployment $1 is already $state, and Central can't drop it: the release is out, so fix forward (RELEASING.md, When a step fails)" ;;
    VALIDATED | FAILED) ;;
    *) fail "deployment $1 is $state: only a VALIDATED or FAILED deployment can be dropped; wait, then drop it" ;;
  esac
  code=$(call DELETE "/api/v1/publisher/deployment/$1")
  case $code in 2??) ;; *) explain "$code" "dropping deployment $1" ;; esac
  say "dropped deployment $1, which was $state"
}

# Each component's POM, from the purls the VALIDATED status in FILE lists, and its jar when it has
# one, served by repo1: what Maven and Gradle download, which can be some minutes after Central
# says PUBLISHED. The purls come from FILE, as Central's status of a PUBLISHED deployment lists
# none.
served() {
  id=$1
  file=$2
  minutes=${3:-30}
  [ -r "$file" ] || fail "no status of deployment $id at $file: served reads the one saved while it was VALIDATED"
  saved=$(jq -r '.deploymentId // empty' "$file" 2> /dev/null) ||
    fail "$file isn't a deployment's status: $(head -c 2000 "$file")"
  [ "$saved" = "$id" ] || fail "$file is the status of deployment ${saved:-(none)}, not $id"
  jq -r '.purls[]? | capture("^pkg:maven/(?<group>[^/]+)/(?<name>[^@]+)@(?<version>[^?#]+)")
    | "\(.group | gsub("\\."; "/"))/\(.name)/\(.version)/\(.name)-\(.version).pom"' "$file" | sort -u > "$tmp/poms"
  [ -s "$tmp/poms" ] ||
    fail "$file lists no Maven components for deployment $id: save its status while it's VALIDATED, as Central lists none once it's PUBLISHED"
  status_json "$id"
  [ "$(state_of)" = PUBLISHED ] || fail "deployment $id is $(state_of), not PUBLISHED"
  deadline=$(($(date +%s) + minutes * 60))
  while :; do
    : > "$tmp/missing"
    while IFS= read -r pom; do
      if ! curl -fsS --max-time 30 -o "$tmp/pom" "$repo1/$pom" 2> /dev/null; then
        echo "$pom" >> "$tmp/missing"
        continue
      fi
      # A component packaged as a jar (the default) or a Maven plugin is a jar beside its POM;
      # a plugin marker or a BOM is the POM alone.
      packaging=$(sed -n 's|.*<packaging>\([^<]*\)</packaging>.*|\1|p' "$tmp/pom" | head -n 1)
      case ${packaging:-jar} in
        jar | maven-plugin)
          jar=${pom%.pom}.jar
          curl -fsSI --max-time 30 -o /dev/null "$repo1/$jar" || echo "$jar" >> "$tmp/missing"
          ;;
      esac
    done < "$tmp/poms"
    if [ ! -s "$tmp/missing" ]; then
      say "repo1 serves all $(wc -l < "$tmp/poms" | tr -d ' ') of deployment $id's components"
      return 0
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      fail "after $minutes minutes, repo1 doesn't yet serve:
$(sed "s|^|  $repo1/|" "$tmp/missing")"
    fi
    say "repo1 doesn't serve $(wc -l < "$tmp/missing" | tr -d ' ') of them yet, such as $(head -n 1 "$tmp/missing")"
    sleep "$poll"
  done
}

[ "$#" -ge 2 ] || usage
command=$1
id=$2
shift 2
# A deployment id is a UUID: anything else would be pasted into a URL.
printf '%s\n' "$id" | grep -Eqx '[0-9a-fA-F-]{8,64}' || fail "'$id' isn't a deployment id"
case $command in
  status) [ "$#" -eq 0 ] || usage && status_json "$id" && cat "$tmp/status" ;;
  wait) [ "$#" -ge 1 ] && [ "$#" -le 2 ] || usage && wait_for "$id" "$@" ;;
  publish) [ "$#" -eq 0 ] || usage && publish "$id" ;;
  drop) [ "$#" -eq 0 ] || usage && drop "$id" ;;
  served) [ "$#" -ge 1 ] && [ "$#" -le 2 ] || usage && served "$id" "$@" ;;
  *) usage ;;
esac
