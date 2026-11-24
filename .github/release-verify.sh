#!/usr/bin/env bash
# A published release as a person gets it, from where they get it: release-verify.yml runs each
# step on Linux, macOS and Windows (Git Bash), in caches of its own, so nothing built here stands
# in for what Central, the Plugin Portal and GitHub serve.
#
#   .github/release-verify.sh wait X.Y.Z [minutes]   until every piece of X.Y.Z is served
#   .github/release-verify.sh cli X.Y.Z              the CLI's zip from the GitHub release
#   .github/release-verify.sh maven X.Y.Z            the Maven plugin, from Central
#   .github/release-verify.sh gradle X.Y.Z           the Gradle plugin, from the Plugin Portal
#
# cli, maven and gradle each run on a copy of samples/pricing, and pass when the run finishes with
# its reports and the score line PricingSampleTest expects of it (the maven step writes the sample
# a pom.xml); maven also runs on samples/maven-modules, for a reactor. Run them from a checkout of
# the release's tag, so the sample and the line are the release's. Each says what it found in
# $GITHUB_STEP_SUMMARY when there is one, and a failure says what to do: Central can't delete, so
# a broken release is fixed by the next patch.
set -u

step=${1:?"usage: release-verify.sh wait|cli|maven|gradle X.Y.Z"}
version=${2:?"usage: release-verify.sh $step X.Y.Z"}
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "release-verify: $version isn't X.Y.Z" >&2; exit 2; }

repo=$(pwd)
work="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/release-verify"
mkdir -p "$work"
summary=${GITHUB_STEP_SUMMARY:-/dev/null}
os=${RUNNER_OS:-$(uname -s)}
central=https://repo1.maven.org/maven2/io/mutastic
portal=https://plugins.gradle.org/m2/io/mutastic
github=https://github.com/mutastic/mutastic/releases/download/v$version
# A clean cache of each tool's own, so everything is fetched from where a user's build fetches it.
export GRADLE_USER_HOME="$work/gradle-home"
m2="$work/m2"

# A path as Java reads it: Windows's Java takes C:/Users/..., not Git Bash's /c/Users/....
native() {
  if command -v cygpath > /dev/null; then cygpath -m "$1"; else printf '%s\n' "$1"; fi
}

# What a step found, for the run's summary as well as its log.
note() {
  printf '%s\n' "$*"
  printf '%s\n' "$*" >> "$summary"
}

# A failed step: what failed, the end of its log, and what to do, then exit 1.
failed() {
  local what=$1 log=${2:-}
  echo "::error::$version on $os: $what"
  {
    printf '### ✗ %s on %s: %s\n\n' "$version" "$os" "$what"
    if [ -n "$log" ] && [ -f "$log" ]; then
      printf '<details><summary>The end of its log</summary>\n\n```\n'
      tail -n 40 "$log"
      printf '```\n</details>\n\n'
    fi
    if [ "$step" != wait ]; then
      printf 'A published version on Central can never be changed or removed: fix forward. Card it\n'
      printf 'on the board, fix the cause, release %s, and run release-verify on that.\n' "$(next_patch)"
    fi
  } >> "$summary"
  [ -n "$log" ] && [ -f "$log" ] && tail -n 40 "$log"
  exit 1
}

next_patch() {
  local major minor patch
  IFS=. read -r major minor patch <<< "$version"
  printf '%s.%s.%s' "$major" "$minor" $((patch + 1))
}

# The line PricingSampleTest expects first from `--format=agent`, at this checkout.
expected_line() {
  grep -o '"mutastic: [A-Z]* · score [^"]*"' \
    mutastic-cli/src/test/java/io/mutastic/cli/PricingSampleTest.java | head -n 1 | tr -d '"'
}

# A fresh copy of a sample.
copy() {
  rm -rf "${work:?}/$2"
  cp -R "samples/$1" "$work/$2"
  printf '%s\n' "$work/$2"
}

# The run in `log` printed `expected` and wrote its reports into `reports`.
check_run() {
  local what=$1 log=$2 reports=$3 expected=$4
  grep -qxF "$expected" < <(tr -d '\r' < "$log") ||
    failed "$what didn't print \`$expected\`" "$log"
  for report in mutation.json mutation.html; do
    [ -s "$reports/$report" ] || failed "$what wrote no $report in $reports" "$log"
  done
  note "- ✓ $what: \`$expected\`, with its reports"
}

# Central's mirrors can lag the file the wait saw, and Maven remembers a miss for a day: try a
# build again, after forgetting what it couldn't resolve, when that's why it failed.
unresolved='could not be resolved|Could not (find|transfer|resolve)|Failed to read artifact descriptor|not found in'
with_retries() {
  local log=$1
  shift
  for attempt in 1 2 3; do
    "$@" > "$log" 2>&1 && return 0
    grep -qE "$unresolved" "$log" || return 1
    echo "try $attempt: something wasn't served yet; trying again in a minute"
    find "$m2" \( -name '*.lastUpdated' -o -name 'resolver-status.properties' \) -delete 2> /dev/null
    sleep 60
  done
  return 1
}

wait_for_release() {
  local minutes=${1:-30}
  local deadline=$(($(date +%s) + minutes * 60))
  local -a wanted=(
    "the CLI on the GitHub release|$github/mutastic-$version.zip"
    "its SHA256SUMS|$github/SHA256SUMS"
    "the CLI on Central|$central/mutastic-cli/$version/mutastic-cli-$version.pom"
    "the Maven plugin on Central|$central/mutastic-maven-plugin/$version/mutastic-maven-plugin-$version.pom"
    "the Gradle plugin on Central|$central/mutastic-gradle-plugin/$version/mutastic-gradle-plugin-$version.pom"
    "the Gradle plugin on the Plugin Portal|$portal/io.mutastic.gradle.plugin/$version/io.mutastic.gradle.plugin-$version.pom"
  )
  local -a waiting=("${wanted[@]}")
  note "Waiting up to $minutes minutes for $version:"
  while :; do
    local -a still=()
    for entry in "${waiting[@]}"; do
      if curl -fsIL --retry 2 --max-time 30 -o /dev/null "${entry#*|}"; then
        note "- ✓ ${entry%%|*}, at $(date -u +%H:%M) UTC"
      else
        still+=("$entry")
      fi
    done
    waiting=("${still[@]+"${still[@]}"}")
    [ ${#waiting[@]} -eq 0 ] && return 0
    if [ "$(date +%s)" -ge "$deadline" ]; then
      for entry in "${waiting[@]}"; do note "- ✗ ${entry%%|*}: not at ${entry#*|}"; done
      failed "not all of it was served within $minutes minutes. The release job attaches the CLI \
once approved; Central serves a deployment about half an hour after it's published in the Central \
Portal; the Plugin Portal has it once portal.yml has run on the tag. Do what's left, then run this \
again: \`gh workflow run release-verify.yml -f version=$version\`"
    fi
    sleep 60
  done
}

# The CLI's zip, checked against the release's SHA256SUMS, unpacked and run.
verify_cli() {
  local expected download=$work/cli
  expected=$(expected_line)
  [ -n "$expected" ] || failed "PricingSampleTest names no score line to expect"
  rm -rf "$download" && mkdir -p "$download"
  (
    cd "$download" &&
      curl -fsSLO --retry 3 "$github/mutastic-$version.zip" &&
      curl -fsSLO --retry 3 "$github/SHA256SUMS"
  ) || failed "the CLI's zip or SHA256SUMS couldn't be downloaded from $github"
  local listed actual
  listed=$(awk -v name="mutastic-$version.zip" '$2 == name || $2 == "*" name { print $1 }' "$download/SHA256SUMS")
  # From stdin: given a path with a backslash, as on Windows, sha256sum escapes its line with one.
  if command -v sha256sum > /dev/null; then
    actual=$(sha256sum < "$download/mutastic-$version.zip" | cut -d' ' -f1)
  else
    actual=$(shasum -a 256 < "$download/mutastic-$version.zip" | cut -d' ' -f1)
  fi
  [ -n "$listed" ] || failed "SHA256SUMS doesn't list mutastic-$version.zip"
  [ "$listed" = "$actual" ] || failed "mutastic-$version.zip is $actual, but SHA256SUMS says $listed"
  note "- ✓ mutastic-$version.zip matches SHA256SUMS ($actual)"
  (cd "$download" && unzip -q "mutastic-$version.zip") || failed "mutastic-$version.zip didn't unpack"
  local command=$download/mutastic-$version/bin/mutastic
  [[ "$os" == Windows ]] && command=$command.bat
  local said
  said=$("$command" --version 2>&1 | tr -d '\r' | head -n 1)
  [ "$said" = "mutastic $version" ] || failed "the CLI's --version says \`$said\`, not \`mutastic $version\`"
  local project log=$work/cli.log
  project=$(copy pricing cli-pricing)
  "$command" run "$(native "$project")" --format=agent > "$log" 2>&1 ||
    failed "the CLI's run on samples/pricing exited $?" "$log"
  check_run "the CLI on samples/pricing" "$log" "$project/build/mutastic" "$expected"
}

# samples/pricing as a Maven project: its sources, and a pom.xml with the same tests and JDK.
pricing_pom() {
  cat > "$1/pom.xml" << 'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>pricing</artifactId>
  <version>1</version>
  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.12.2</version>
      <scope>test</scope>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.5.4</version>
      </plugin>
    </plugins>
  </build>
</project>
POM
}

verify_maven() {
  local expected
  expected=$(expected_line)
  [ -n "$expected" ] || failed "PricingSampleTest names no score line to expect"
  rm -rf "$m2"
  local goal="io.mutastic:mutastic-maven-plugin:$version:run"
  local project log=$work/maven-pricing.log
  project=$(copy pricing maven-pricing)
  rm -rf "$project"/build.gradle.kts "$project"/settings.gradle.kts "$project"/gradle "$project"/gradlew*
  pricing_pom "$project"
  with_retries "$log" mvn -B -ntp -Dmaven.repo.local="$(native "$m2")" -f "$(native "$project/pom.xml")" \
    "$goal" -Dmutastic.format=agent ||
    failed "\`mvn $goal\` on samples/pricing failed" "$log"
  check_run "\`mvn $goal\` on samples/pricing" "$log" "$project/target/mutastic" "$expected"

  # A reactor: each module mutated, each with its own reports.
  log=$work/maven-modules.log
  project=$(copy maven-modules maven-modules)
  with_retries "$log" mvn -B -ntp -Dmaven.repo.local="$(native "$m2")" -f "$(native "$project/pom.xml")" \
    "$goal" -Dmutastic.format=agent ||
    failed "\`mvn $goal\` on samples/maven-modules failed" "$log"
  for module in core app; do
    [ -s "$project/$module/target/mutastic/mutation.json" ] ||
      failed "\`mvn $goal\` on samples/maven-modules wrote no report for $module" "$log"
  done
  local passed
  passed=$(grep -c '^mutastic: PASS · score ' < <(tr -d '\r' < "$log"))
  [ "$passed" -eq 2 ] || failed "\`mvn $goal\` on samples/maven-modules passed $passed of its 2 modules" "$log"
  note "- ✓ \`mvn $goal\` on samples/maven-modules: both modules pass, with their reports"
}

verify_gradle() {
  local expected
  expected=$(expected_line)
  [ -n "$expected" ] || failed "PricingSampleTest names no score line to expect"
  local project log=$work/gradle.log
  project=$(copy pricing gradle-pricing)
  local build=$project/build.gradle.kts
  local applied
  applied=$(awk -v version="$version" '
    /^plugins \{$/ { print; getline; print; print "    id(\"io.mutastic\") version \"" version "\""; next }
    { print }' "$build")
  printf '%s\n' "$applied" > "$build"
  grep -q "id(\"io.mutastic\") version \"$version\"" "$build" ||
    failed "samples/pricing's build.gradle.kts has no plugins block to apply the plugin in"
  local wrapper=./gradlew
  [[ "$os" == Windows ]] && wrapper=./gradlew.bat
  (cd "$project" && with_retries "$log" "$wrapper" --no-daemon -q mutasticRun -Pmutastic.format=agent) ||
    failed "\`id(\"io.mutastic\") version \"$version\"\` on samples/pricing failed" "$log"
  check_run "\`id(\"io.mutastic\") version \"$version\"\` on samples/pricing" "$log" \
    "$project/build/mutastic" "$expected"
}

case $step in
  wait) wait_for_release "${3:-30}" ;;
  cli) verify_cli ;;
  maven) verify_maven ;;
  gradle) verify_gradle ;;
  *) echo "release-verify: no step $step: wait, cli, maven or gradle" >&2; exit 2 ;;
esac
