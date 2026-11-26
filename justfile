# The build's verbs, one word each, the same in both editions (mutastic-rs has the Rust one): `just`
# lists them. A recipe is one line over Gradle or a script, so the words outlive the tool beneath.

set minimum-version := "1.59.0"
set default-list
# A recipe's arguments reach its commands as "$@", each as it was typed: a glob or a space in one
# isn't split or expanded again.
set positional-arguments

# Windows runs recipes in Git Bash's sh, which looks for a bare name on the PATH, not here: so
# the wrapper is named with ./ there too.
gradle := if os_family() == "windows" { "./gradlew.bat" } else { "./gradlew" }
launcher := if os_family() == "windows" { "mutastic.bat" } else { "mutastic" }
mutastic := "mutastic-cli/build/install/mutastic/bin/" + launcher

# Windows CI's two shares of the CLI's end-to-end tests (test-windows).
windows-share-1 := "--tests io.mutastic.cli.CardsTest --tests io.mutastic.cli.PricingSampleTest --tests io.mutastic.cli.MavenPluginTest"
windows-share-2 := "--tests io.mutastic.cli.GradlePluginTest --tests io.mutastic.cli.GateTest --tests io.mutastic.cli.QuarkusTest"

# Turns on the commit message hook and installs the docs site's packages
[group('setup')]
setup:
    git config core.hooksPath .githooks
    npm ci --prefix docs

# Compiles and packages every module, without the checks
[group('build')]
build:
    {{ gradle }} assemble

# Runs mutastic from here: just run run samples/pricing
[group('build')]
run *args: install
    {{ mutastic }} "$@"

# Builds the mutastic command into mutastic-cli/build/install
[group('build')]
install:
    {{ gradle }} :mutastic-cli:installDist

# Formats the code
[group('check')]
fmt:
    {{ gradle }} spotlessApply

# Checks the format, Error Prone, NullAway, Checkstyle and the javadoc, without the tests
[group('check')]
lint:
    {{ gradle }} check -x test -x coverageReport -x coverageCheck

# Runs the tests, or those the arguments pick: just test -p mutastic-cli --tests '*CommandTest'
[group('check')]
test *args:
    {{ gradle }} test "$@"

# Everything a pull request needs to pass: the checks, the tests and the coverage floor
[group('check')]
check:
    {{ gradle }} build

# What CI runs on a pull request: the build, the SBOM and the HTML report's page
[group('check')]
ci: && report-ui-check
    {{ gradle }} build cyclonedxBom

# Writes the coverage report over every module's tests, in build/reports/jacoco
[group('check')]
coverage:
    {{ gradle }} coverageReport

# Writes every card on the samples again, after a change to their words: read the diff
[group('docs')]
cards:
    {{ gradle }} :mutastic-cli:exportCards

# Writes the docs' reference and the output they show again, after a change to a command
[group('docs')]
docs-output:
    {{ gradle }} :mutastic-cli:exportDocsModel :mutastic-cli:exportDocsOutput

# Writes the kept ids again, after a new operator: read the diff
[group('docs')]
ids:
    {{ gradle }} :mutastic-cli:exportIds

# Serves the docs site at localhost:4321, reloading as you edit
[group('docs')]
[working-directory('docs')]
docs-dev:
    npm run dev

# Builds the docs site into docs/dist, as deployed
[group('docs')]
[working-directory('docs')]
docs-build:
    npm run build

# Builds the HTML report's page, or embeds a run in it: --embed mutation.json out.html
[group('build')]
report-ui *args:
    node report-ui/build.mjs "$@"

# Writes gradle.lockfile again, after changing a version
[group('build')]
lock:
    {{ gradle }} lockDependencies --write-locks

# Prints the next release's changelog section, as it would read
[group('merge')]
changelog-preview *args:
    sh scripts/changelog.sh preview "$@"

# Checks the changelog's entries, and that only a release edits CHANGELOG.md
[group('merge')]
changelog-check *args:
    sh scripts/changelog.sh check "$@"

# Merges a pull request through the merge queue: just merge 59 body.txt [--wait]
[group('merge')]
merge N *body:
    sh .githooks/merge-pr "$@"

# Moves the docs' Rust pin to a mutastic-rs commit and stamps the shared pages it changed: read them
[group('docs')]
docs-pin-rust COMMIT:
    sh scripts/docs-pin-rust.sh "$@"

# Stamps shared pages once they say what the Rust docs do: just docs-stamp concepts/results
[group('docs')]
[working-directory('docs')]
docs-stamp +PAGES:
    npm run --silent stamp-shared -- "$@"

# Lists where the editions' captured output differs in shape, against a mutastic-rs docs folder
[group('docs')]
docs-compare *RUST_DOCS:
    node docs/scripts/compare-captures.mjs "$@"

# The release, in RELEASING.md's order. Agents may run release-prepare, release-check,
# release-verify, release-hold and candidate; release, release-portal and docs-redeploy are
# Brian's. [confirm] only asks: `--yes` skips it, so the `release` environment's approval in CI is
# what stands between a recipe and a published release.

# Runs the release candidate, the CLI from here over a share of every sample: just candidate --of=8
[group('release')]
candidate *args:
    bash scripts/candidate-run.sh "$@"

# Makes the release commit on a branch and opens its pull request: just release-prepare 1.4.0 <rust commit>
[group('release')]
release-prepare VERSION *RUST_COMMIT:
    sh scripts/release-prepare.sh "$@"

# Checks what a release needs, all at once, writing nothing tracked: just release-check 1.4.0
[group('release')]
release-check VERSION:
    sh scripts/release-check.sh "$@"

# Brian's: tags main's head vVERSION and pushes it, which stages the release for its one approval
[confirm("Tag main's head and push it? release.yml then uploads it to Central unpublished, and publishes it only once the release environment's reviewer approves")]
[group('release')]
[script('bash')]
release VERSION:
    set -euo pipefail
    # An -rc is a GitHub pre-release, and its tag starts no release (RELEASING.md).
    [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "release: '$1' isn't a version, X.Y.Z" >&2; exit 2; }
    sh scripts/changelog.sh notes "$1" > /dev/null
    git fetch --quiet origin main
    head=$(git rev-parse FETCH_HEAD)
    git tag "v$1" "$head"
    git push origin "refs/tags/v$1"
    echo "Pushed v$1 at $head: approve its release run once its summary reads right (gh run watch)"

# Brian's: sends the Gradle plugin of vVERSION to the Plugin Portal, should release.yml's step fail
[confirm("Publish the Gradle plugin to the Gradle Plugin Portal? portal.yml runs on the tag, waiting on the release environment's approval")]
[group('release')]
release-portal VERSION:
    gh workflow run portal.yml --ref "v$1"

# Checks a release as a person gets it, on Linux, macOS and Windows: just release-verify 1.4.0
[group('release')]
release-verify VERSION:
    gh workflow run release-verify.yml -f version="$1"

# Holds the next release to VERSION, once it's on Central, on a branch from main: commit the diff
[group('release')]
release-hold VERSION:
    sh scripts/hold-release.sh "$@"

# Brian's: deploys docs.mutastic.io again, every release line's docs from its own tag, by hand
[confirm("Deploy docs.mutastic.io again, every release line rebuilt from its tag?")]
[group('release')]
docs-redeploy:
    gh workflow run docs.yml --ref main -f release=true

# CI's jobs, each a part of what `just ci` runs, so a job can be run here as CI runs it.

# The build without the CLI's end-to-end tests and the coverage floor, which have jobs of their own
[group('ci')]
ci-build:
    {{ gradle }} build cyclonedxBom -x :mutastic-cli:test -x coverageReport -x coverageCheck

# Runs a share of the CLI's end-to-end test classes: just test-cli 2/4
[group('ci')]
test-cli SHARE:
    {{ gradle }} :mutastic-cli:test -Pmutastic.shard="$1"

# Runs Windows share 1 (cards, both build tools) or 2 (Gradle plugin, gate, Quarkus) of the CLI's
[group('ci')]
test-windows SHARE:
    {{ gradle }} :mutastic-cli:test {{ if SHARE == "1" { windows-share-1 } else if SHARE == "2" { windows-share-2 } else { error("A Windows share is 1 or 2, not " + SHARE) } }} --continue

# Checks report.html is the build of report-ui's sources, and renders each view in Chrome
[group('ci')]
report-ui-check:
    node report-ui/build.mjs --check
    node --test report-ui/test/*.test.mjs

# Checks the coverage floor over the build's coverage and the CLI shares', a folder each in DIR
[group('ci')]
[script('bash')]
coverage-merge DIR:
    set -euo pipefail
    # Each share's files, named apart, where the coverage tasks read the CLI's.
    outside=mutastic-cli/build/jacoco/outside
    mkdir -p "$outside"
    for shard in "$1"/*; do
      name=$(basename "$shard")
      cp "$shard/test.exec" "$outside/$name-test.exec"
      for file in "$shard"/outside/*.exec; do
        cp "$file" "$outside/$name-$(basename "$file")"
      done
    done
    {{ gradle }} coverageReport coverageCheck -x test

# Runs one project of the corpus as the nightly does, in GitHub Actions only: just corpus NAME
[group('ci')]
corpus *args:
    bash scripts/corpus-run.sh "$@"

# Runs mutastic on a share of one of its modules, no threshold: just self-shard mutastic-engine 1/4
[group('ci')]
self-shard MODULE SHARE *args: install
    m="$1" s="$2" && shift 2 && {{ mutastic }} run ":$m" --threshold=0 --shard="$s" "$@"

# Puts each module's shares, in DIR/<module>, together as its whole run, held to its floor
[group('ci')]
[script('bash')]
self-merge DIR: install
    set -uo pipefail
    shares="$1"
    failed=0
    floor() {
      {{ mutastic }} run ":$1" --from-shards="$shares/$1" \
        --threshold="$2" --format=jsonl > "$1.jsonl" || failed=1
      grep '"reason":"run-finished"' "$1.jsonl" \
        | python3 -c 'import json,sys; e=json.load(sys.stdin); print(sys.argv[1], e["mutants"], "mutants, score", e["score"], "against", e["threshold"], "passed" if e["passed"] else "FAILED")' "$1" \
        || true
    }
    # Floors under the scores, raised as tests are added: never lowered.
    floor mutastic-engine 53
    floor mutastic-report 60
    floor mutastic-api 50
    exit $failed

# Checks the justfile's format, and that its verbs are those .github/just-verbs.txt lists
[group('ci')]
[script('bash')]
justfile-check:
    set -euo pipefail
    just="{{ just_executable() }}"
    "$just" --justfile justfile --fmt --check
    if ! "$just" --justfile justfile --summary | tr ' ' '\n' | diff -u .github/just-verbs.txt -; then
      echo "The verbs aren't those .github/just-verbs.txt lists (-), but the justfile's (+)." >&2
      echo "People and agents type them: keep a verb, or say it's gone in the list and the docs." >&2
      exit 1
    fi
