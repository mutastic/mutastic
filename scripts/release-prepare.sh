#!/bin/sh
# Makes the release commit and opens its pull request (RELEASING.md), so no step of it is
# remembered by hand: `just release-prepare X.Y.Z` runs it.
#
#   scripts/release-prepare.sh [--dry-run] X.Y.Z                       a patch
#   scripts/release-prepare.sh [--dry-run] X.Y.0 <mutastic-rs commit>  a minor or major release
#
# On a branch release-X.Y.Z from main as GitHub has it, it gathers changes/ into CHANGELOG.md's
# section for X.Y.Z (changelog.sh release). For X.Y.0, which the editions release together, it
# also moves the docs' Rust pin to the Rust edition's release commit (docs-pin-rust.sh), which
# stamps again the shared pages whose Rust sources changed; a patch takes no commit, and keeps the
# pin it has. It commits that as "chore(release): version X.Y.Z", checks the commit with
# `release-check.sh --no-build`, then pushes the branch and opens the pull request. A stamped page
# has to say what the Rust edition now does before the release merges, which only a reader can
# judge, so with one the pull request is a draft listing them.
#
# --dry-run does all of it here, from whatever is checked out, and stops before the push: the
# branch and its commit stay, to read. It never tags or releases; `just release` does that.
set -eu
usage() {
  sed -n '5,6s/^# *//p' "$0" >&2
  exit 2
}
cd "$(dirname "$0")/.."

dry_run=false
if [ "${1:-}" = --dry-run ]; then
  dry_run=true
  shift
fi
[ "$#" -ge 1 ] && [ "$#" -le 2 ] || usage
version=$1
rust=${2:-}
if ! printf '%s\n' "$version" | grep -Eqx '[0-9]+\.[0-9]+\.[0-9]+'; then
  echo "release-prepare: '$version' isn't a version, X.Y.Z" >&2
  exit 2
fi
case $version in
  *.0)
    if [ -z "$rust" ]; then
      echo "release-prepare: $version releases with the Rust edition: name its release commit," >&2
      echo "  chore(release): version $version on mutastic-rs's main, for the docs' pin" >&2
      exit 2
    fi
    ;;
  *)
    if [ -n "$rust" ]; then
      echo "release-prepare: $version is a patch, which keeps the docs' Rust pin: drop '$rust'" >&2
      exit 2
    fi
    ;;
esac

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
body=$tmp/body
branch=release-$version
subject="chore(release): version $version"
if [ -n "$(git status --porcelain)" ]; then
  echo "release-prepare: the tree has changes: commit or stash them first" >&2
  exit 1
fi
if git rev-parse --verify --quiet "refs/heads/$branch" > /dev/null; then
  echo "release-prepare: there's a branch $branch already: delete it, or finish it" >&2
  exit 1
fi
git fetch --quiet origin main
if [ "$(git rev-parse HEAD)" != "$(git rev-parse origin/main)" ]; then
  if [ "$dry_run" = false ]; then
    echo "release-prepare: a release starts from main as GitHub has it: git switch main && git pull" >&2
    exit 1
  fi
  echo "release-prepare: not on origin/main, which a dry run allows"
fi

git switch --quiet -c "$branch"
sh scripts/changelog.sh release "$version"
entries=$(git status --porcelain -- changes | wc -l | tr -d ' ')

stamped=
if [ -n "$rust" ]; then
  pin_status=0
  sh scripts/docs-pin-rust.sh "$rust" > "$tmp/pin" || pin_status=$?
  cat "$tmp/pin"
  [ "$pin_status" -eq 0 ] || exit "$pin_status"
  stamped=$(sed -n 's|^  /\(.*\)/$|\1|p' "$tmp/pin")
  pinned=$(node -p "const pin = require('./docs/rust-edition.json'); pin.version + ' at ' + pin.commit.slice(0, 7)")
fi

{
  echo "The $version release commit: CHANGELOG.md's section, gathered from"
  echo "the $entries entries waiting in changes/."
  if [ -n "$rust" ]; then
    echo "The docs pin the Rust edition's release commit, mutastic-rs $pinned."
  fi
  if [ -n "$stamped" ]; then
    echo "These shared pages were stamped again, and must say what the Rust"
    echo "edition $version does before this merges:"
    printf '%s\n' "$stamped" | sed 's|^|- /|; s|$|/|'
  fi
} > "$body"
git add -A
printf '%s\n\n%s\n' "$subject" "$(cat "$body")" | git commit --quiet --file -
echo "release-prepare: committed $subject on $branch"

if ! sh scripts/release-check.sh --no-build "$version"; then
  echo "release-prepare: put right what release-check lists, on $branch, then push it yourself" >&2
  exit 1
fi

if [ "$dry_run" = true ]; then
  echo "release-prepare: a dry run, so nothing is pushed and no pull request is opened."
  echo "  Read it: git show $branch"
  echo "  Throw it away: git switch - && git branch -D $branch"
  exit 0
fi

git push --quiet -u origin "$branch"
draft=
[ -z "$stamped" ] || draft=--draft
# shellcheck disable=SC2086 # --draft, or nothing
gh pr create $draft --base main --head "$branch" --title "$subject" --body-file "$body"
