#!/usr/bin/env bash
# Which Windows shares of a run didn't pass because their runner broke, and whether any share's
# tests failed: `again=[1,2]` and `tests-failed=true|false`, as lines for GITHUB_OUTPUT.
#
#   windows-faults.sh <owner/repo> <run id> <run attempt>
#
# A runner breaks in two ways, and neither is the change under test. It stops answering, and the
# job is cut off when its time is up: the job is cancelled or timed out. Or it can no longer start
# java.exe or node.exe (0xC0000142): the tests fail, and so does every step after them, the
# reports' upload included, since actions run on node. A share whose tests failed on a runner that
# still worked uploads its reports, and is the change's own failure.
set -euo pipefail
repo=$1 run=$2 attempt=$3

jobs=$(gh api --paginate "repos/$repo/actions/runs/$run/attempts/$attempt/jobs?per_page=100" --jq '.jobs[]')
jq -rs '
  def share: .name | capture("^windows-share \\((?<share>[0-9]+)") | .share | tonumber;
  def uploadFailed: any(.steps[]?; (.name | test("upload-artifact")) and .conclusion == "failure");
  def broke: .conclusion == "cancelled" or .conclusion == "timed_out"
    or (.conclusion == "failure" and uploadFailed);
  [ .[] | select(.name | test("^windows-share \\([0-9]+")) | select(.conclusion != "success" and .conclusion != "skipped") ]
  | "again=\([ .[] | select(broke) | share ] | unique | tojson)",
    "tests-failed=\(any(.[]; broke | not))"
' <<<"$jobs"
