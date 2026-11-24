#!/usr/bin/env bash
# Fetches what the CLI's tests' Maven builds need from Central before they run, trying again
# when Central doesn't serve it.
#
# Central now and then answers a plugin it has with "not found" (maven-resources-plugin 3.4.0,
# 19 and 20 Nov), and Maven keeps that answer in the local repository for a day, so every Maven
# test after it failed too. Maven's own retries are for a dropped connection, not for that. So
# this builds a copy of each Maven sample, up to its tests (`verify`: the plugins mutastic's runs
# use and the ones its tests run), with `-U`, and when a build doesn't succeed it forgets Maven's
# records of what it couldn't resolve, waits and tries again. A test that fails in a sample is
# its own business here, so test failures don't fail the build.
#
# The Maven plugin's test (MavenPluginTest) has a local repository of its own, beside the
# repository the build publishes to; it gets a copy of what was fetched.
#
#   .github/maven-warm.sh
set -u

local_repository="$HOME/.m2/repository"
# Windows's Java reads C:/Users/..., not Git Bash's /c/Users/....
if command -v cygpath > /dev/null; then
  local_repository=$(cygpath -m "$local_repository")
fi
plugin_test_repository=build/samples-maven-local
work="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/maven-warm"
# Maven's records of what it couldn't fetch, so it asks Central again rather than answer "absent"
# from them: a cached run's or a try before's.
forget() {
  for repository in "$local_repository" "$plugin_test_repository"; do
    [ -d "$repository" ] || continue
    find "$repository" \( -name '*.lastUpdated' -o -name 'resolver-status.properties' \) \
      -delete 2> /dev/null
  done
}
unresolved='could not be resolved|Could not (find|transfer|resolve)|Failed to read artifact descriptor'

forget
for attempt in 1 2 3 4; do
  failed=""
  for sample in maven-modules maven-suites maven-by-name maven-selection maven-assertions; do
    rm -rf "${work:?}/$sample"
    mkdir -p "$work"
    cp -R "samples/$sample" "$work/$sample"
    mvn -B -ntp -U -Dmaven.repo.local="$local_repository" -Dmaven.test.failure.ignore=true \
      -f "$work/$sample/pom.xml" verify > "$work/$sample.log" 2>&1
    if ! grep -q 'BUILD SUCCESS' "$work/$sample.log"; then
      failed="$failed $sample"
      echo "$sample, try $attempt: the build failed:"
      { grep -E "$unresolved" "$work/$sample.log" || tail -n 15 "$work/$sample.log"; } |
        head -n 15 | sed 's/^/  /'
    fi
  done
  if [ -z "$failed" ]; then
    echo "Every Maven sample's plugins and dependencies are in $local_repository (try $attempt)."
    mkdir -p "$plugin_test_repository"
    cp -R "$local_repository/." "$plugin_test_repository/"
    exit 0
  fi
  forget
  if [ "$attempt" -lt 4 ]; then
    sleep $((attempt * 20))
  fi
done
echo "::error::The Maven samples didn't build after 4 tries:$failed." \
  "Where Maven couldn't resolve a plugin or dependency, Central didn't serve it: rerun the job."
exit 1
