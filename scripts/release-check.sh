#!/bin/sh
# Checks what has to hold before vX.Y.Z is released, all at once: one exit code, and a list of
# what's wrong. RELEASING.md runs it on the release commit, and CI on a release pull request.
#
#   scripts/release-check.sh [--no-build | --build] X.Y.Z
#   scripts/release-check.sh [--no-build | --build] --subject <subject>
#
# - the changelog: X.Y.Z has entries, and a patch has no scoring changes (changelog.sh check --for);
# - the docs' Rust pin, docs/rust-edition.json: a commit of the Rust edition's X.Y, its Cargo.toml
#   that version, and every shared page stamped from the Rust docs there (check-editions.mjs);
# - the editions' contract: Java has no pending entry without an issue and a target, or with a
#   target gone by, and no dated one at its end (docs/scripts/contract.mjs release);
# - ids: ./gradlew :mutastic-cli:exportIds leaves src/test/ids as committed;
# - Central: checkCentralFiles, the files a release uploads, within its share of the month's.
#
# The last two build, so take minutes: --no-build skips them, --build runs only them. With
# --subject, the version is the one a release's commit subject names, "chore(release): version
# X.Y.Z", with or without its " (#N)"; any other subject has nothing to check. It writes nothing
# that's tracked, and publishes and pushes nothing.
set -u
usage() {
  sed -n '5,6s/^# *//p' "$0" >&2
  exit 2
}
cd "$(dirname "$0")/.."

checks=all
case ${1:-} in
  --no-build) checks=quick && shift ;;
  --build) checks=build && shift ;;
esac
if [ "${1:-}" = --subject ]; then
  [ "$#" -eq 2 ] || usage
  version=$(printf '%s\n' "$2" | sed -nE 's/^chore\(release\): version ([0-9]+\.[0-9]+\.[0-9]+)( \(#[0-9]+\))?$/\1/p')
  if [ -z "$version" ]; then
    echo "release-check: \"$2\" isn't a release, so there's nothing to check"
    exit 0
  fi
else
  [ "$#" -eq 1 ] || usage
  version=$1
fi
if ! printf '%s\n' "$version" | grep -Eqx '[0-9]+\.[0-9]+\.[0-9]+'; then
  echo "release-check: '$version' isn't a version, X.Y.Z" >&2
  exit 2
fi
line=${version%.*}

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
: > "$tmp/problems"

# problem <what's wrong>
problem() {
  printf '%s\n' "$*" >> "$tmp/problems"
}

# step <name>: says what's being checked.
step() {
  echo "release-check: $1"
}

# A command's failure lines become problems, each line one; the rest of what it says goes to $tmp/log.
# run <prefix> <command...>
run() {
  prefix=$1
  shift
  if ! "$@" > "$tmp/log" 2>&1; then
    grep -E "^($prefix|  - )" "$tmp/log" | sed -E "s/^($prefix:? |  - )//" > "$tmp/found"
    if [ -s "$tmp/found" ]; then
      while IFS= read -r found; do problem "$found"; done < "$tmp/found"
    else
      tail -n 20 "$tmp/log" | sed 's/^/    /' >&2
      problem "$* failed: what it printed is above"
    fi
    return 1
  fi
}

# unchanged <command> <path>: running the command leaves the path as committed.
unchanged() {
  command=$1
  path=$2
  if [ -n "$(git status --porcelain -- "$path")" ]; then
    problem "$path has changes not committed: commit or stash them, then check again"
    return
  fi
  if ! sh -c "$command" > "$tmp/log" 2>&1; then
    tail -n 20 "$tmp/log" | sed 's/^/    /' >&2
    problem "$command failed: what it printed is above"
    return
  fi
  changed=$(git status --porcelain -- "$path")
  if [ -n "$changed" ]; then
    problem "$command changed $path: new ids, of an operator new since the last release; read the diff and commit it, so they're held from $version on: $(printf '%s' "$changed" | tr '\n' ' ')"
  fi
}

if [ "$checks" != build ]; then
  step "the changelog for $version"
  run changelog sh scripts/changelog.sh check --for "$version"

  step "the docs' Rust pin, and the shared pages' stamps"
  if command -v node > /dev/null; then
    pinned=$(node -p "require('./docs/rust-edition.json').version")
    case $pinned in
      "$line".*) ;;
      *) problem "docs/rust-edition.json pins the Rust edition's $pinned, not a $line.*: move it to the Rust release commit with scripts/docs-pin-rust.sh <commit> (RELEASING.md)" ;;
    esac
    run '\[check-editions\]' sh -c 'cd docs && MUTASTIC_RS_DOCS=pinned node scripts/check-editions.mjs'
    step "the editions' contract: no pending entry for java that isn't registered"
    # Its problems are its "  - " lines; no line starts with the prefix, so its count isn't one.
    run 'contract-release' node docs/scripts/contract.mjs release java "$version"
  else
    problem "node isn't on the PATH, and the docs' checks need it (Node 24)"
  fi
fi

if [ "$checks" != quick ]; then
  step "ids, with exportIds"
  unchanged "./gradlew --quiet :mutastic-cli:exportIds" mutastic-cli/src/test/ids
  step "the files a release uploads to Central"
  if ./gradlew checkCentralFiles > "$tmp/log" 2>&1; then
    sed -n 's/^A release uploads/  A release uploads/p' "$tmp/log"
  else
    sed -n 's/.*\(A release would upload .*\)/\1/p' "$tmp/log" > "$tmp/found"
    [ -s "$tmp/found" ] || { tail -n 20 "$tmp/log" >&2 && echo "checkCentralFiles failed: what it printed is above" > "$tmp/found"; }
    while IFS= read -r found; do problem "$found"; done < "$tmp/found"
  fi
fi

count=$(wc -l < "$tmp/problems" | tr -d ' ')
if [ "$count" -eq 0 ]; then
  echo "release-check: $version is ready to release"
  exit 0
fi
echo "release-check: $version isn't ready, $count to put right:" >&2
sed 's/^/  - /' "$tmp/problems" >&2
exit 1
