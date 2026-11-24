#!/bin/sh
# Tests scripts/hold-release.sh against the hold made by hand for 1.3.0 (#183): run on the tree
# just before it, with the person's one edit (the keys 1.3.0 added to the every-key file) made as
# #183 made it, the script writes #183's tree exactly, and running it again changes nothing.
#
#   scripts/test-hold-release.sh
#
# It builds mutastic as it was at 1.3.0 in a worktree of its own, for exportIds, so it takes a
# few minutes. It needs the whole history and the tags (git fetch --tags --unshallow in a shallow
# clone). It doesn't ask Maven Central.

set -eu
cd "$(dirname "$0")/.."

hold=$(git log --format=%H -1 --grep='^build: hold the next release to 1\.3\.0 ' HEAD)
if [ -z "$hold" ]; then
  echo "No commit holding the next release to 1.3.0 in this history: fetch the whole of it." >&2
  exit 1
fi
work=$(mktemp -d)
trap 'git worktree remove --force "$work/tree" > /dev/null 2>&1 || true; rm -rf "$work"' EXIT
git worktree add -q --detach "$work/tree" "$hold^"
cp scripts/hold-release.sh "$work/tree/scripts/hold-release.sh"
every_key=mutastic-cli/src/test/resources/released/1.3.0/every-key/mutastic.yaml

fail() {
  echo "test-hold-release: $1" >&2
  exit 1
}

# What differs from #183, the new script aside, tracked or not.
differences() {
  (
    cd "$work/tree"
    git add -A
    git diff --cached --name-status "$hold" -- . ':(exclude)scripts/hold-release.sh'
  )
}

echo "Holding 1.3.0 on $(git rev-parse --short "$hold^"), the tree before #183."
(cd "$work/tree" && sh scripts/hold-release.sh --offline-check-skip 1.3.0) > "$work/first.txt"
sed 's/^/  /' "$work/first.txt"
for key in 'rules\[\]\.output   (new since 1\.2\.1)' 'exclude-tests   (new since 1\.2\.1)'; do
  grep -q "^  $key$" "$work/first.txt" || fail "the reminder doesn't list $key"
done
[ "$(differences)" = "M	$every_key
M	$every_key.read" ] || fail "only the every-key file should differ before the person's edit:
$(differences)"

echo "Adding the keys 1.3.0 added, as #183 did, and holding again."
git show "$hold:$every_key" > "$work/tree/$every_key"
(cd "$work/tree" && sh scripts/hold-release.sh --offline-check-skip 1.3.0) > /dev/null
[ -z "$(differences)" ] || fail "the tree isn't #183's:
$(differences)"

echo "Holding once more: nothing changes."
(cd "$work/tree" && sh scripts/hold-release.sh --offline-check-skip 1.3.0) > /dev/null
[ -z "$(differences)" ] || fail "a second run changed the tree:
$(differences)"

if (cd "$work/tree" && sh scripts/hold-release.sh 1.3) > /dev/null 2>&1; then
  fail "a version that isn't X.Y.Z was taken"
fi
if (cd "$work/tree" && sh scripts/hold-release.sh --offline-check-skip 9.9.9) > /dev/null 2>&1; then
  fail "a version with no tag was held"
fi
echo "hold-release.sh reproduces #183."
