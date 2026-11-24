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
ci:
    {{ gradle }} build cyclonedxBom
    node report-ui/build.mjs --check
    node --test report-ui/test/*.test.mjs

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

# Merges a pull request through the merge queue: just merge 59 body.txt [--wait]
[group('merge')]
merge N *body:
    sh .githooks/merge-pr "$@"
