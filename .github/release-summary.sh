#!/bin/sh
# What the approver of a release reads before approving it, made from what the release workflow
# built and uploaded, not written by a person or an agent: the version, the Central deployment
# and its counts against Central's monthly limits, the release's notes and the links to check.
# release.yml writes it to the run's summary, above the approval.
#
#   .github/release-summary.sh VERSION CENTRAL_STATUS_JSON FILES NOTES [DRAFT_URL]
#
# CENTRAL_STATUS_JSON is `scripts/central.sh status`'s output, FILES the count checkCentralFiles
# printed, NOTES the release's section of CHANGELOG.md. With GH_TOKEN and GITHUB_REPOSITORY set, it
# counts the releases already published this month (each Java release is one on Central).
set -eu
[ "$#" -ge 4 ] && [ "$#" -le 5 ] || { sed -n '7s/^# *//p' "$0" >&2 && exit 2; }
version=$1
status=$2
files=$3
notes=$4
draft=${5:-}
cd "$(dirname "$0")/.."

# Central's limits for the namespace, as its Usage page showed them on 20 Nov 2026: that page is
# the source of truth (central.sonatype.org/publish/maven-central-publishing-limits), and these
# are only what the summary compares with. A release's own share is held in build.gradle.kts.
month_files=${CENTRAL_MONTHLY_FILES:-1000}
month_releases=${CENTRAL_MONTHLY_RELEASES:-7}
per_release=$(sed -n 's/^val centralFilesPerRelease = \([0-9]*\)$/\1/p' build.gradle.kts)

repo=${GITHUB_REPOSITORY:-mutastic/mutastic}
server=${GITHUB_SERVER_URL:-https://github.com}
id=$(jq -r .deploymentId "$status")
state=$(jq -r .deploymentState "$status")
components=$(jq '.purls // [] | length' "$status")

# The releases published this month, before this one.
month=$(date -u +%Y-%m)
before=
if [ -n "${GH_TOKEN:-}" ] && command -v gh > /dev/null; then
  before=$(gh api --paginate "repos/$repo/releases" \
    --jq ".[] | select(.draft | not) | select((.published_at // \"\") | startswith(\"$month\")) | .tag_name" 2> /dev/null |
    grep -c . || true)
fi

echo "## Releasing mutastic $version"
echo
echo "Approving the \`release\` environment publishes it, in this order: Maven Central, the"
echo "Gradle Plugin Portal, the GitHub release, docs.mutastic.io; then it opens the hold pull"
echo "request. Rejecting it drops the Central deployment, and nothing is published."
echo
echo "| | |"
echo "| --- | --- |"
echo "| Version | \`$version\`, tag \`v$version\` at \`${GITHUB_SHA:-$(git rev-parse HEAD)}\` |"
echo "| Central deployment | \`$id\`, $state |"
echo "| Components | $components |"
if [ -n "$per_release" ]; then
  echo "| Files on Central | $files, of the $per_release a release may upload (build.gradle.kts), and Central's $month_files a month |"
else
  echo "| Files on Central | $files, of Central's $month_files a month |"
fi
if [ -n "$before" ]; then
  with=$((before + 1))
  over=
  [ "$with" -le "$month_releases" ] || over=": **over the limit**, so read the usage page before approving"
  echo "| Releases this month | $with with this one ($before published before it), of Central's $month_releases a month$over |"
fi
echo
if [ "$components" -gt 0 ]; then
  echo "<details><summary>The $components components</summary>"
  echo
  jq -r '.purls[] | "- `\(.)`"' "$status"
  echo
  echo "</details>"
  echo
fi
echo "### Links"
echo
[ -z "$draft" ] || echo "- The draft release, its assets attached: $draft"
echo "- The deployment in the Central Portal: https://central.sonatype.com/publishing/deployments"
echo "- Central's usage this month: https://central.sonatype.com/publishing/usage"
echo "- The changes since the last release: $server/$repo/blob/v$version/CHANGELOG.md"
if [ -n "${GITHUB_RUN_ID:-}" ]; then
  echo "- This run, to approve or reject: $server/$repo/actions/runs/$GITHUB_RUN_ID"
fi
echo
echo "### The notes"
echo
cat "$notes"
