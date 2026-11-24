#!/bin/sh
# Whether a release's commit is one main has and CI passed: its run in the merge queue, or on the
# push to main, succeeded. The release job builds only what ships, not the tests, so it releases
# only a tree they passed on. Says which, or why not, and exits 1.
#
#   .github/release-tested.sh <owner/repo> <commit>
#
# Needs GH_TOKEN, with contents and actions read.
set -eu
[ "$#" -eq 2 ] || { sed -n '6s/^# *//p' "$0" >&2 && exit 2; }
repo=$1
commit=$2

# main has the commit when main is it or is ahead of it.
on_main=$(gh api "repos/$repo/compare/$commit...main" --jq .status)
case $on_main in
  ahead | identical) ;;
  *)
    echo "$commit isn't on main: release a commit the merge queue landed"
    exit 1
    ;;
esac

runs=$(gh api "repos/$repo/actions/workflows/ci.yml/runs?head_sha=$commit&per_page=100" \
  --jq '.workflow_runs[] | select(.event == "merge_group" or .event == "push")
    | "\(if .event == "push" then "on the push to main" else "in the merge queue" end)\t\(.conclusion // .status)\t\(.html_url)"')
passed=$(printf '%s\n' "$runs" | awk -F '\t' '$2 == "success"' | head -n 1)
if [ -n "$passed" ]; then
  printf '%s\n' "$passed" | awk -F '\t' -v commit="$commit" '{ print "CI passed on " commit " " $1 ": " $3 }'
  exit 0
fi
if [ -z "$runs" ]; then
  echo "CI has no merge queue or push run for $commit: release a commit the merge queue landed"
  exit 1
fi
echo "CI hasn't passed on $commit:"
printf '%s\n' "$runs" | awk -F '\t' '{ print "  " $1 ", " $2 ": " $3 }'
echo "When one passes, as it finishes or once you run it again, run this job again."
exit 1
