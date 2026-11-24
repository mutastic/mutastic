#!/bin/sh
# Moves the docs' pin of the Rust edition, docs/rust-edition.json, to a mutastic-rs commit, then
# stamps again the shared pages whose Rust sources changed since, as scripts/check-editions.mjs
# names them. For X.Y.0 it's run once the Rust edition's release commit is on its main, before this
# edition's vX.Y.0 is tagged (RELEASING.md):
#
#   scripts/docs-pin-rust.sh <commit>     a full hash, or a branch or tag of mutastic-rs
#
# The version comes from that commit's Cargo.toml, read from a shallow fetch, so the pin can't name
# a commit of another release. It writes the pin and docs/src/shared-sources.json, and commits and
# pushes nothing: read the diff, and each restamped page against what changed in mutastic-rs.
set -eu
[ "$#" -eq 1 ] || {
  echo "usage: scripts/docs-pin-rust.sh <mutastic-rs commit>" >&2
  exit 2
}
ref=$1
cd "$(dirname "$0")/.."
pin=docs/rust-edition.json
field() {
  node -p "JSON.parse(require('fs').readFileSync('$pin', 'utf8')).$1"
}
repository=$(field repository)
before=$(field commit)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

git -C "$tmp" init --quiet
if ! git -C "$tmp" fetch --quiet --depth 1 "$repository" "$ref"; then
  echo "docs-pin-rust: can't fetch '$ref' from $repository: give a commit's full hash, a branch or a tag" >&2
  exit 1
fi
commit=$(git -C "$tmp" rev-parse 'FETCH_HEAD^{commit}')
version=$(git -C "$tmp" show FETCH_HEAD:Cargo.toml | sed -n 's/^version = "\(.*\)"/\1/p' | head -n 1)
if ! printf '%s\n' "$version" | grep -Eqx '[0-9]+\.[0-9]+\.[0-9]+'; then
  echo "docs-pin-rust: $commit's Cargo.toml has no version X.Y.Z ('$version')" >&2
  exit 1
fi

PIN=$pin VERSION=$version COMMIT=$commit node -e '
  const fs = require("fs");
  const pin = JSON.parse(fs.readFileSync(process.env.PIN, "utf8"));
  pin.version = process.env.VERSION;
  pin.commit = process.env.COMMIT;
  fs.writeFileSync(process.env.PIN, `${JSON.stringify(pin, null, 2)}\n`);
'
echo "docs-pin-rust: $pin pins mutastic-rs $version at $commit"

cd docs
pages=$(MUTASTIC_RS_DOCS=pinned node scripts/check-editions.mjs --stale)
if [ -z "$pages" ]; then
  echo "docs-pin-rust: no shared page's Rust sources changed, so none is stamped"
else
  # shellcheck disable=SC2086 # one page to a word
  MUTASTIC_RS_DOCS=pinned npm run --silent stamp-shared -- $pages
  echo "docs-pin-rust: stamped these shared pages, each of which must now say what mutastic-rs $version"
  echo "does. Read what changed in their sources and bring each up to date before committing:"
  echo "  git -C ~/Code/mutastic-rs diff $(printf '%.7s' "$before") $(printf '%.7s' "$commit") -- docs/"
  # shellcheck disable=SC2086 # one page to a word
  printf '  /%s/\n' $pages
fi
# What else the move needs, such as an AWAITING_RUST entry for the release before.
MUTASTIC_RS_DOCS=pinned node scripts/check-editions.mjs
