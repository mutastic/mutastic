# Changes

What changes from one release of mutastic's Java edition to the next, written as changes land. The
newest come first. A release's notes on GitHub are its section here. What each release may change
is on the [Compatibility](https://docs.mutastic.io/reference/compatibility/) page; the Rust
edition's changes are in [its own changelog](https://github.com/mutastic/mutastic-rs/blob/main/CHANGELOG.md).

## Unreleased

### Progress

- **A run is never silent.** Every phase is named while it runs and ends with a line: `building`
  (the build tool's task, its latest log line under it), `built`, `coverage` (tests run of those
  planned), `mutants`, `ran`, and `checking` (each catching test run again on the unmutated code,
  the record and the reports), then `checked`. On a terminal a clock moves every 100 ms; anywhere
  else a line is printed at least every 30 seconds (`MUTASTIC_HEARTBEAT` changes that), Gradle's
  plugin and CI logs included. [PROGRESS.md](PROGRESS.md) is the contract, shared with the Rust
  edition.
- **Progress asks whether stderr is a terminal,** not stdin and stdout: `mutastic run > out.txt`
  keeps the live line. The start script says so in `MUTASTIC_STDERR_TTY`; CI, `TERM=dumb` and a
  coding agent get counter lines instead.
- **`--format=agent` and `--format=jsonl` show progress on stderr,** as counter lines, so an agent's
  harness doesn't stop a quiet run; `--progress=none` or `MUTASTIC_PROGRESS=none` silences it.
- **The live block has a bar, and says what's left.** The coverage pass's bar counts its tests; the
  mutants' is weighted, each mutant counting for what its covering tests took in the coverage pass,
  so it moves with the time passed rather than the count. `~21m left` shows once three mutants and
  ten seconds have run. Heartbeat lines carry the percent and what's left too.
- **A line for each worker,** with its own `[HH:MM:SS]` timer and what it runs: the test in the
  coverage pass, or a mutant as `Pricing.java:17 discount() >= becomes >`. `SLOW` marks a mutant at
  three times what its tests took or half its timeout, and a test after a minute. Up to eight show
  (four below 100 columns), then `… and 3 more running`.
- **`t` says what's running,** above the live block: each worker's mutant, its id, its timer against
  what it should take, and the tests that cover it. `kill -USR1`, and Ctrl-T on macOS and the BSDs,
  do the same anywhere, piped or not. Ctrl-C and Ctrl-Z still work, and every way out leaves the
  terminal as it was.
- **Every line of the live block fits the terminal:** the bar shrinks first, then the counts lose
  their words, then a line is cut with `…`, and wrapping is off while it's drawn. The start script
  passes the width in `MUTASTIC_COLUMNS`; without it, `COLUMNS`, or 100.
- **The summary ends on the score, with its verdict beside it:** `Score  90.9%  [PASS] 1 missed,
  above the threshold of 80.` The verdict names the threshold (`[FAIL] Below the threshold of 80: 6
  missed, 0 not covered.`), so the row no longer says `threshold 80` apart. `Next:` moves above the
  rows, under the misses it's about, and the Duration row names the build and the checking after
  the mutants beside setup, coverage and mutants.
- **`--format=jsonl` says how the run is going, as it goes.** Three events join
  `mutastic.events/v1`: `phase-started` and `phase-finished` (with `durationMs`) as each phase starts
  and ends, and `progress` on the heartbeat's cadence, with the phase's clock, its count, and for the
  mutants the weighted fraction, what's left, the fates and the busy workers. They're additive: a
  reader that ignores reasons it doesn't know reads the stream as before.
- **The terminal's tab shows the run** where the terminal draws progress there (Windows Terminal,
  ConEmu, Ghostty, WezTerm; `MUTASTIC_TERM_PROGRESS=true|false` decides elsewhere): busy while
  building and checking, the mutants' weighted percent while they run, red once the score of twenty
  or more falls below the threshold (never with `--fail-on-new`), and cleared on every way out,
  Ctrl-C, kills and Ctrl-Z included.
- **Each frame is painted at once:** on a terminal, the live block's redraw is one write wrapped in
  synchronized output, which terminals that know it paint without flicker.
- **Gradle's progress line follows the run:** `> :mutasticRun > mutants 2871/5525 · 52% · ✗ 41
  missed · ~21m left`, from the run's live events. It's Gradle's internal API, so a Gradle without
  it shows the heartbeat's lines alone.

### Fixes

- **A build of modules names them as commands, for Maven as for Gradle.** At the top of a Maven
  build with no Java code of its own, mutastic said `name a module with some: mutastic run
  :<module>` and named none. Now both tools say `<directory> is a build's root, with no Java code
  of its own.`, name its modules with Java code, and end in `mutastic run :app` for each, up to five.
- **A module the build hasn't got is said to be missing.** `mutastic run :nope` answered with
  `the Maven build failed` and a log, after making `nope/target/mutastic`, or with Gradle's whole
  failure. Now it says `the build has no module :nope.`, names the modules there are, and makes
  nothing. A close name, `:cor`, gets `There's one called :core.` and its command. Gradle, which
  took `:cor` for `:core` and ran it, now says so too.
- **A directory inside a build names the build.** Run in a build's `src`, mutastic said it isn't a
  Gradle or Maven build, with commands to fill in. Now it says which build it's inside and ends in
  the command that tests it: `mutastic run ..`, or `mutastic run ../.. :app` for a Maven module,
  which is tested from the top of its build.
- **A close name is said, with the command corrected.** A mistyped command, option, value,
  `mutastic.` property, `mutastic.yaml` section or rule, rules-file key, operator, filter or mutant
  id now gets the one name close to it, as a statement, never run in its place:
  `run has no option --formt. There's one called --format.`, then the command line as typed with
  only that word changed, `mutastic run --format=agent`, its value after an `=`. Close means the
  same but for case, else within a third of the name's length in edits, two letters swapped
  counting as one; an id must be one slip away.
- **A usage error prints one line of usage,** the command's own, `usage: mutastic run [DIR]
  [:MODULE] [OPTIONS]`, not every command's. A mistake in `mutastic.yaml`, a rules file,
  `.mutastic/ignores.yaml`, an environment variable or the git repository prints none: it isn't
  the command line's.
- **Config errors name the line,** for an operator, filter or plugin in `mutastic.yaml` and an entry
  in `.mutastic/ignores.yaml`, and list what's valid when nothing is close. `operators: [stronger]`,
  which mutastic doesn't read, is shown written an item a line, rather than as an unknown section.
- **`--report`'s error lists `sarif`,** which it has taken since 1.0.0.
- **A build that fails is quoted at your line.** Gradle's failure came whole, its `* Try:` advice
  and all, and Maven's only as a log's path. Now mutastic says `pricing doesn't build, before any
  mutant is in it.`, names the compiler's error at your file and line (the first three, of how
  many), or what the tool says went wrong, and ends in the build's own command, `./gradlew
  testClasses` or `mvn test-compile`: the Rust edition's words.
- **A failing test says what it said, and how to run it alone.** `the tests fail without any
  mutant: <test>; their output is in <log>` is now `<test> fails without any mutant, so mutastic
  can't judge one.`, then `It failed: expected: <24> but was: <25>`, the log, and the build's
  command for that test, `./gradlew test --tests '…'` or `mvn test -Dtest='…'`.
- **A test that ends the JVM, or tests that run past 10 minutes, exit with 3,** as tests failing
  with no mutant in do, naming the test that was running, not with 4 and `internal error:
  java.lang.IllegalStateException`. A file mutastic can't read or write (a read-only build
  directory, a file in the way, a full disk) exits with 2 and the system's reason, and a module
  whose tests none ran on the JUnit Platform says so, with 2.

### Agents

- **A failure is an event too.** In `--format=agent` and `--format=jsonl`, an error is also the
  last line on stdout, a `run-failed` event in `mutastic.events/v1`, the Rust edition's shape:
  `code` (`unknown-option`, `build-failed`, `red-baseline`, …), `message`, `where` (the directory
  as typed and its whole path), `help` (each command the error ends in, with whether it runs as it
  is, has a `<placeholder>`, or is a close name to check) and `exitCode`, with `rendered`, the
  words stderr has. A `--hook` run writes none. Before, a red baseline in `jsonl` wrote
  `run-started` and stopped.
- **An errors page** in the reference lists every code with what mutastic says on a sample, why and
  what to do, and how the Rust edition's exit statuses differ until 2.0.

## 1.1.0 (12 Nov 2026)

### Card changes

- **A suggested test is named by where its input sits, not its value.** A number in a name goes
  stale when the spec moves, and no guide or big suite names a test that way. A call of one
  argument is named for zero or a negative input, `feeWhenAmountIsZero`, or for the edge of the
  literal its condition compares with, `discountAtTheTotalEdge` and
  `discountJustBelowTheTotalEdge`; a constant the source names is used as before,
  `discountAtFreeFrom`; anything else keeps its values, `isFreeWithTotal0ForFreeFrom50`. The value
  stays in the call.
- **A card names an object made where it's used.** A comparison or sum whose side is a call on
  `new Something(…)` was named without its receiver: the card said `length() != 0` for
  `new Measured(a).length() != 0`, a condition the source doesn't have. The receiver is now read
  back as `new Measured(a)`.
- **Each suggested test says what it's for.** A card with a test for more than one of its mutants
  gave only the first mutant's fix, so the second test came with nothing saying why. Now `help:`
  says `one test for each mutant`, and each test follows its own mutant's fix and id; the agent
  format's `fix:` does the same, with a `fix for <id>:` line before each test.
- **Never change production code to make a mutant go away.** The agent format's `rules:` line now
  says `add or change tests only; never change production code to make a mutant go away: tell the
  person`, and the gate's open and block messages say the same, as in the Rust edition. Production
  code changed to pass a test the spec asks for is the task; code changed so a mutant can't exist
  hides what the mutant found, and was the one cheat mutastic's own evals saw.

### Additions

- **`run --fail-on-new`, for a project whose score can't reach the threshold yet.** The run
  passes while every mutant no test catches is in `.mutastic/baseline.yaml`, and fails, exit 1, on
  one that isn't, whatever the score: the threshold isn't applied. The summary says `No new misses:
  12 in the baseline.` or names the new ones, as does the agent format's first line, whose entries
  put the new misses first, and the GitHub Actions job summary; `run-finished` gains `failOnNew`, `newMisses` and `inBaseline`. Without
  a baseline it's a usage error, exit 2, saying `mutastic baseline` writes one. The build plugins
  take it as `failOnNew`. See [Brownfield: fail only on new misses](https://docs.mutastic.io/guides/ci/#brownfield-fail-only-on-new-misses).
- **Annotations and a job summary on GitHub Actions.** Where `GITHUB_ACTIONS=true`, a full run
  annotates the line of each of its first ten misses, as the agent format ranks them, and adds a
  Markdown summary to the job's page (`$GITHUB_STEP_SUMMARY`): the verdict, the score, the counts
  and the first misses. No token or permission; the annotations go on stderr, so a format's own
  output is untouched. `MUTASTIC_GITHUB=off` turns both off. See
  [Running in CI](https://docs.mutastic.io/guides/ci/#on-github-actions).
- **`explain --format=jsonl`, for agents.** `mutastic explain ID --format=jsonl` writes the
  events a run's JSON Lines has for that one mutant, then a new `mutant-explained` event with each
  row of About this mutant as a field, in the same words: the bytecode change and what each opcode
  does, the operator, how the id is made, the site and the last run. The events schema describes
  it; a v1 reader that doesn't know it skips it. `explain` refuses the formats it has no
  explanation in. See [One mutant, explained](https://docs.mutastic.io/concepts/output/#one-mutant-explained).
- **`extends:` in `mutastic.yaml`.** A module's file adds another's rules, plugins and filters
  when it says `extends: ../mutastic.yaml`; its own `operators:`, if any, replace the other's. See
  [In a build of several modules](https://docs.mutastic.io/guides/configuration/#in-a-build-of-several-modules).
- **`--report=sarif`, for code scanning.** It writes `mutation.sarif`, SARIF 2.1.0: a result for
  each missed mutant (a warning) and each one no test reaches (a note), on its line, with the
  card's headline, keyed by the mutant's id in `partialFingerprints` so an alert follows its
  mutant; never a fix. Paths are from the git repository's root. See
  [Report files](https://docs.mutastic.io/reference/reports/).
- **The Gradle plugin runs a Kotlin Multiplatform module.** It configures on the multiplatform
  plugin as well as `java`, reading the JVM target's compilations, its `Test` task and its runtime
  configuration, as the `mutastic` command's init script already did.
- **Links to each operator's docs.** `explain`'s About this mutant gives the operator's row on the
  [operators page](https://docs.mutastic.io/concepts/operators/), `docs:
  https://docs.mutastic.io/concepts/operators/#boundary`, in the docs of the release running. On a
  terminal with links on (`--hyperlinks`), a card's `^^ changed to` mark links there too, and
  `--format=jsonl`'s mutant events carry it as `docs`. A plugin's operator has no row, so no link.

### Scoring changes

- **An index compared with -1 skips what differs only below -1.** `s.indexOf(c) == -1` made
  `<= -1`, and the like, can't be told apart, since `indexOf` gives -1 or an index: skipped as
  equivalent for `indexOf` and `lastIndexOf` on `String`, `StringBuilder` and `StringBuffer`, and
  `Character.digit`. A `List`'s runs. Scores can go up; no id changes.
- **A module uses its nearest `mutastic.yaml`, however it's run.** `mutastic run :core` from the
  build's top, the Gradle plugin's task and `mvn mutastic:run` used to read only the top's file,
  while a run in `core/` read only `core/mutastic.yaml`, so the same module could get two scores.
  Now each reads `core/mutastic.yaml` for `core` when there is one, and the top's when there
  isn't. A build that keeps rules in a module's file, and runs from the top, now has them applied.
- **A never-negative claim the compiler checked is trusted.** A comparison's change that differs
  only below zero is skipped where the value compared is annotated `@NonNegative`, `@Positive` or
  `@IndexFor` (Checker Framework), or AndroidX `@IntRange` with `from >= 0`, on a parameter, field,
  local or method return; the reason names the annotation. Annotations only checked at run time,
  or by nothing, don't count. Scores can go up on code that has them; no id changes.
- **Only the JDK's own counts are taken as never negative.** A size or length comparison that
  differs only below zero was skipped for any `java.util` `size()` or `CharSequence` `length()`,
  though a project's own class can return -1. Now only an array's length, `length()` on `String`,
  `StringBuilder`, `StringBuffer` and `StringJoiner`, and a few final JDK classes' counts are.
  Those mutants on a project's own collections now run, so scores can go down; no id changes.
- **An edge inside an `a != b` guard is skipped as `equivalent:`.** In `if (a != b) return a < b;`
  the boundary mutant `a <= b` differs only when `a == b`, which the guard rules out, so no test
  could catch it. Proved from the bytecode, for `int` and `long` locals; fields, floats and doubles
  still run. Scores can go up; no id changes.

### Fixes

- **Two runs on one module wait for each other.** Run at once (an agent's gate and a person's
  run), they wrote each other's setup, staged classes and records in the module's work directory
  in the middle of a run. The first now holds the directory until it ends, and a second says it's
  waiting and runs after it. Runs inside one JVM, as a gate's are, don't wait on each other.
- **Kover's agent is left out of the tests too.** A Gradle build that applies Kover adds its agent
  to the tests, and its jar, which Kover writes in a task of its own before them, wasn't there
  when mutastic ran them: the test worker didn't start, and the run stopped with an internal
  error. Kover's agent and IntelliJ's, which it's built on, are left out as JaCoCo's is, and the
  run is the same as without Kover.
- **A Maven build's JaCoCo agent is left out of the tests, as Gradle's is.** mutastic leaves
  JaCoCo out because it changes classes as they load, but it knew the agent only by Gradle's jar
  name, `jacocoagent.jar`. Under Maven's `prepare-agent` (`org.jacoco.agent-…-runtime.jar`) the
  agent stayed in, and on a Quarkus project every mutant was reported as not covered, with tests
  that plainly reached them.
- **Tests that pass but reach nothing stop the run, instead of scoring 0%.** When the tests ran
  and passed but the coverage pass recorded no line of any class with a mutant, mutastic scored
  every mutant as not covered: a confident wrong answer when the cause was an agent or a class
  loader that kept its coverage out. Now the run exits with status 2 and says what happened, the
  `-javaagent:` arguments it saw, and the likely causes. A run of only some mutants (`--only`,
  `--since`, `--shard`), or with `--exclude-tests`, is scored as before.

## 1.0.0 (11 Nov 2026)

mutastic 1.0.0: the Java edition and the Rust edition, released together at the same version.

From 1.0.0, what the [Compatibility](https://docs.mutastic.io/reference/compatibility/) page promises holds until 2.0: mutant ids, the events format (`mutastic.events/v1`), the plugin API, configuration and the command line. This release makes the last breaking changes before that promise starts; they're listed first.

```sh
curl -fsSL https://mutastic.io/install.sh | sh
```

Maven Central: `io.mutastic:*:1.0.0`. Gradle plugin: `id("io.mutastic") version "1.0.0"`. Docs: [docs.mutastic.io](https://docs.mutastic.io).

### Breaking changes

Before 1.0.0 there are no users to keep, so these are made outright, with no deprecation. From 1.0.0 the events format only gains.

- **Events: the old names are gone (both editions).** `run-started`'s `rules` (read `rule`); `mutant-finished`/`mutant-revised`'s `killedBy` and `killedWith` (read `caughtBy` and `caughtWith`); a mutation's `bytecode` (read `applied`, whose `text` is the same, with `level` `bytecode` from Java and `source` from Rust); `run-finished`'s counts keyed by status, `Killed`, `Survived`, `Timeout`, `NoCoverage`, `Ignored` and Rust's `RuntimeError` (read `killed`, `survived`, `timeout`, `noCoverage`, `ignored`, `runtimeError`); a scoped run's `scope.files` and `scope.lines` (read `scope.fileCount` and `scope.lineCount`). A status's value (`"status": "Killed"`) is unchanged. The `mutation.json` report keeps its schema's `killedBy`.
- **Events: every event names its edition, and the schema requires what every run writes.** The Java edition now writes `edition` on every event, first after `schema`, where it wrote it only on `run-started` and `run-finished`; an event without one is no longer taken as Java's. The schema now requires `edition`, `rule`, a mutation's `applied`, a missed mutant's `ignore` and `propose` (of both editions), `run-finished`'s lowerCamel counts, `toolchain`, `workers`, `workersStarted` and `coverageMs`, and `next-finished`'s `waiting`. `caughtBy` stays optional: a timeout names no test, and neither does a crash the Rust edition counts as caught.
- **Compatibility checks start at 1.0.0.** The plugin API check, the events-schema check and the released-rules-file check compare with the last 1.0+ release (`lastStableRelease` in `gradle.properties`), so none ran against 0.5.0; the Rust edition gains the same for its commands, options, variables, exit codes, `mutastic.yaml` and rules files. `Filter` joins `Mutator` and `ClassMutator` under the plugin API promise.
- **The native `mutastic` runs the newest Java edition patch of its own major.minor from 1.0.0** (mutastic-rs), rather than the newest kept or the latest; `MUTASTIC_VERSION` still picks one exactly (see Installing).
- **The agent format's `or propose` line changed (both editions).** It's now `mutastic ignore <id> --propose --as=<equivalent|unreachable|…> --reason="Differs only when <case>. That can't happen because <why>." <project>`, and `ignore --propose --as=equivalent` (or `unreachable`) refuses a reason without a "because", as it does any reason still holding `<case>` or `<why>`. A script or skill that ran the old line as printed needs to fill in the kind and the two sentences. From 1.0.0 the agent format's structure is fixed and its words may change (Output page).
- **Rust: the summary counts an ignore by its kind, as Java's does.** `1 skipped (1 user)` is now `1 skipped (1 ignored as equivalent)` (or `1 ignored` when `--as` gave no kind); a `mutastic.yaml` rule's skip is still `user`. Ignores aren't skips, so the old count is gone with no alias. The events are unchanged and the same as Java's: the mutant's `skipKind` is `user`, and `run-finished`'s `ignored` counts every skip. No id or score changes (mutastic-rs c35c169).
- **Two operator ids are gone (Java).** `mutastic/return-default` is now `mutastic/return-value` (defaults) and `mutastic/negate-conditional` is now `mutastic/other-comparison` (stronger); `operators:` in `mutastic.yaml` or `--operators` naming an old id stops with "no operator or set is called …". Rename it. Their mutants' ids changed too, so an ignore recorded against one no longer matches (see Scoring changes).
- **`stronger` no longer means every operator.** It's `defaults` plus the stronger tier; `all` is every operator, and `extended` adds the extended tier to another set.
- **Plugin API (Java).** `Mutator.Words` has a fourth part, `written`: a plugin that builds `Words` with the canonical constructor must pass it (the three-argument constructor stays). What's marked `@Internal` (the result model, build tools, `Policy`, `BuiltIn` and a few methods of `Mutant` and `Project`) isn't part of the promise: a plugin that named a `BuiltIn` constant should name the operator by its id.
- **A word a command doesn't take is refused with exit status 2 (Java),** where it was dropped, as the Rust edition already did. A script that passed stray words needs them removed.
- **A run fails, with exit status 3, when a test that caught mutants fails on its own (Java),** whatever the score; `verify` and `gate check` don't pass on it either. Fix the test's dependence on another test, or leave it out with `--exclude-tests` (see Scoring changes).

### Operator sets

- A new `extended` tier for operators on modern APIs, and a new `all` set. `--operators` and `operators:` take `defaults`, `stronger` (defaults plus the stronger tier), `extended` (the extended tier alone, added to another set: `--operators=defaults,extended`) and `all` (every operator). `stronger` meant every operator before; with no extended operators yet, it selects the same operators as it did.

### Scoring changes

- **`mutastic/return-default` is replaced by `mutastic/return-value` (defaults).** A return now becomes the value its kind of method most plausibly gets wrong: a `boolean` the other one (a computed one gets both), a number `0` (or `1` where it returns `0`), `Optional`, `String`, collections and `Stream` their empty value, boxed numbers as their primitive, anything else `null`. Every return mutant's id changes, an ignore recorded against a return-default mutant no longer matches any mutant, and scores move: methods returning `boolean` get up to two mutants per return, and `return 0;` / `return false;` are no longer skipped as equivalent but changed to the other value. On the pricing sample, 8 of 9 caught with 2 skipped became 10 of 11 caught.

- **`mutastic/remove-discarded-call` is added to `defaults`.** A call whose result is thrown away (`items.add(item);`, `seen.put(k, v);`, `text.append(x);`) is removed. Runs get more mutants, and scores move with them.

- **`mutastic/negate-conditional` is replaced by `mutastic/other-comparison` (stronger), and `stronger` gains `mutastic/comparison`.** Together with `mutastic/boundary` and `mutastic/remove-conditional`, `stronger` now makes every change a comparison can have (the full relational set): `mutastic/comparison` adds the one-sided change of the sufficient set (`<` to `!=`, `<=` to `==`, `==` to `<=` and `>=`, `!=` to `<` and `>`), and `mutastic/other-comparison` the rest plus the opposite condition that negate-conditional made. Every negated-condition mutant's id changes (the operator is now in its fingerprint), an ignore recorded against one no longer matches, and `stronger` runs get more mutants, so their scores move. `defaults` is unchanged: `mutastic/boundary` keeps its id and its mutants. The minimal set was meant to replace boundary and negate in `defaults`; measured alone on mutastic's own modules its one-sided change left 156 survivors, roughly 85% of them noise, so it went to `stronger` instead.

- **A comparison of a size with zero gets each change once (defaults).** `mutastic/boundary` no longer changes `size() > 0` to `size() >= 0` (or the like on a length, array length or parameter count): for a value that can't be negative that's the condition always true, which `mutastic/remove-conditional` already makes. Runs have slightly fewer boundary mutants, and scores move a little; no other mutant's id changes.

- **`mutastic/logical-connector` is added to `defaults`.** In a condition of two tests, `&&` becomes `||` and `||` becomes `&&`. Runs get more mutants, and scores move with them.

- **`mutastic/remove-assignment` is added to `defaults`.** An assignment to a variable that already has a value is removed. Runs get more mutants, and scores move with them; no existing mutant's id changes.

- **`library/swap-string` and `library/swap-math` are added to `defaults`.** `startsWith` and `endsWith`, `toUpperCase` and `toLowerCase`, `stripLeading` and `stripTrailing`, `Math.min` and `max`, `Math.floor` and `ceil` swap places. Runs get more mutants, and scores move with them; no existing id changes.

- **`mutastic/string-constant` knows an exception by its real supertypes (stronger).** A class named `...Exception` or `...Error` that isn't a `Throwable` now keeps its string mutants, and a project's exception with any other name has its message skipped as arid. If a class's supertypes can't be found, the mutant stays. A failed check's message is skipped only for `Objects.requireNonNull` and Guava's `Preconditions`, no longer for any class called `Preconditions`. `stronger` scores move on code where a name and a type disagree; on mutastic's own engine nothing moved. No id changes.

- **`stream/remove-stage` no longer removes `limit` from a stream only it ends (defaults).** On a stream the method visibly starts from `Stream.iterate(seed, next)` or `generate`, or their `IntStream`, `LongStream` and `DoubleStream` forms, the mutant is now skipped as `unviable:`, because the tests would hang and a hang counted as a catch shows nothing. A timeout used to count as caught, so scores on code like this can go down a little. The same now goes for `ints()`, `longs()` and `doubles()` given no size, with or without a range, on `Random`, `SecureRandom`, `ThreadLocalRandom`, `SplittableRandom` and `RandomGenerator`, and for a limit worked out with arithmetic, as `limit(n + 1)`. The `iterate` with a condition keeps its mutant, and so do `ints(5)`, a stream with `takeWhile`, another `limit`, or a later `findFirst` or `anyMatch`, and a limit with a `?:` in it. No id changes.

- **Kotlin support is experimental.** It runs, and the changes below make its cards truer, but it hasn't yet been proved on real Kotlin projects, Jetpack Compose among them.

- **Kotlin: kotlinc's own code is skipped (Kotlin projects only).** kotlinc's `Intrinsics` checks and a data class's `copy`, `componentN` and `equals` are now skipped as `generated:`, so those projects' scores change. No id moves, and Java projects aren't affected. Kotlin mutants now point at the real `.kt` file (Gradle only).

- **Kotlin: a mutant is on the line its code is on (Kotlin projects only).** kotlinc numbers code it copies in from an inline function past the end of the file, with a map in the class file that mutastic now reads. A copy of the project's own inline function is on that function's line, in that function's file, and runs; a copy from outside the module, like the loop in `filter`, is skipped as `generated:` and leaves the score. An inline function's own body, which the JVM never runs, no longer reads as not covered: it's skipped as `generated:` if copies of it exist, and stays as it was if Java calls it or nothing does. No id moves, Kotlin's or Java's: an id doesn't hold a line, and what's skipped still counts as a site. Java projects aren't affected. Kotlin scores move, since mutants leave.

- **Kotlin: a coroutine's state machine and a `lateinit`'s check are skipped, and `!!` isn't (Kotlin projects only).** What kotlinc writes to make a function suspend (`throwOnFailure`, the comparison with `COROUTINE_SUSPENDED`, saving and restoring locals, the `switch` on `label`) and a `lateinit`'s getter and check are skipped as `generated:`. Kotlin scores move, and often down, as some of these were caught by the coroutine breaking, which said nothing of the tests. `x!!` and `x as String` used to be skipped as kotlinc's own check, and now run, as the person wrote them; `x!!.size` is skipped as `equivalent:`. A mutant before a Kotlin method's first line reported line 1 and not covered however the method was tested: it's on the first line now, so some not-covered mutants become caught or missed. No id moves. Java projects aren't affected.

- **Kotlin: suggested tests are in Kotlin (Kotlin projects only).** A mutated `.kt` file gets `fun`, no `void`, `plusOne(1)` and not `ProbeKt.plusOne(1)`, `Counter(1).next(1)` with no `new`, `1.doubled()` for an extension and `Counter(2).twice` for a property. A call that can't be sure to compile gets no suggestion: a generic function or class, `@JvmName`, an `object` or companion's member, a file-private class, or a test class in Java. Cards name `name.isEmpty()` for `length() == 0`, "a lambda in `labels()`" for `labels$lambda$0` and a suspending lambda's `invokeSuspend`, and drop `local3` and `x$iv`. Scores don't change. No id moves.

- **Code a test class runs outside its tests counts for the tests that depend on it (all frameworks).** What a `@BeforeAll`/`@AfterAll`, JUnit 4 `@BeforeClass`/`@ClassRule`, TestNG `@BeforeClass`/`@BeforeMethod`/suite methods, a test class's constructor, field and static initialisers, or a `@MethodSource` runs was put down to whichever test happened to run before it, often in another class: its mutants were missed, or "not covered" on TestNG, and cards said "covered by" a test that never ran the code. Now it counts for every test of its class (a `@MethodSource` for its test; TestNG's suite methods and class construction for every test). Scores go up where setup does real work (on the lifecycle samples 50 of 100 such mutants were caught before, all 100 after; on mutastic's own engine, whose truth tests now hold their fixtures per test, it hardly moves). A test that passes among the others but fails on its own is now found the first time it catches, named as flaky, and its catches don't count, so a score can also go down where one was catching by accident. No id changes.

- **A test that caught mutants and fails on its own fails the run (any project).** Once the mutants have run, each test that caught one runs again, on its own with no mutant in, in a new worker. One that fails there (it needs another test's set-up, or trips on something an earlier test or the run left behind) is named as flaky, as a test that "passes among the others but fails on its own"; its catches don't count, and every mutant it reached runs again without it, so scores can go down. Unlike other flaky tests it fails the run whatever the score, with exit status 3, and `verify` and `gate check` don't pass on it either; `--exclude-tests` leaves such a test out. Projects whose suites are order-dependent, or whose tests share files or a database without cleaning up, are the ones that could see it; a healthy suite's results don't change, at the cost of one run of each catching test (about 9 s on mutastic's own engine). In `--format=jsonl`, a mutant run again comes as a `mutant-revised` event, naming the test as `falseCatchBy`. No id changes.

- **Kotlin: what the Compose compiler writes is skipped, and so are kotlinc's default stand-ins and a lambda's `Unit` (Kotlin projects only).** In a `@Composable` (one taking a `Composer` and its masks), the runtime's bookkeeping (groups, `remember`'s cache, `changed`, skipping, trace and source information, the restart scope), the `$changed` and `$default` masks and what works on them, and the zero or `null` a call passes for an argument left to its default are skipped as `generated:`. So are the mask and stand-ins of any call to a Kotlin `f$default` or `DefaultConstructorMarker` constructor, and the `Unit` a lambda returns where the source returns nothing. On mutastic's Compose sample under `defaults`, 249 mutants that ran are skipped now (218 missed, 31 caught only by the compiler's code breaking) and the score went from 25.5% to 55.2%. Cards call Compose's `Text` `Text` (not `Text--4IGK_g`), place a `ComposableSingletons` lambda in its `.kt` file as "a composable lambda", and don't list a `$changed` mask among the inputs seen. No id moves. Java projects aren't affected.

- **Lombok: a `@Cleanup`'s close is skipped, and a `@NonNull` check in a setter Lombok writes runs (Lombok projects only).** The close a `@Cleanup` writes into a method, `if (Collections.singletonList(r).get(0) != null) r.close()`, is now skipped as `generated:`, as a try-with-resources's close is. Its cards asked for a test where the resource was null. The null check a `@NonNull` asks for in a setter Lombok writes now runs, as the check in the person's own methods already did; the rest of the setter stays skipped. Members Lombok marks `@lombok.Generated` (by default from Lombok 1.18.34) were already skipped and still are; with an older Lombok, set `lombok.addLombokGeneratedAnnotation = true` (docs: Results, Lombok). Scores move a little on Lombok projects that use `@Cleanup` or `@NonNull` on fields with setters. No id changes, and projects without Lombok aren't affected.

- **A test class whose setup fails now and then is left out as flaky, and the run goes on (projects with such a class, any framework).** A `@BeforeAll`, `@AfterAll`, JUnit 4's or TestNG's `@BeforeClass`, a class rule or a static initialiser that failed once before any mutant used to stop the run with exit status 3, naming the class by the platform's id (`[engine:junit-jupiter]/[class:…]`). The class now runs again as a class; if it passes, it's left out with every test in it and named (`flaky: SetupTest as a whole (its setup or teardown, outside any test) failed, then passed when run again; left out of this run with every test in it…`), and the run carries on. What only its tests reach is counted as not covered, so those projects get a score where they got none, and its tests catch nothing, as its setup could fail against any mutant. A class that fails every time still stops the run, and the message names it and says its setup or teardown failed outside any test. No id changes; a project whose tests pass before any mutant sees nothing new.

- **Rust edition: `mutastic/unwrap-try` is in the defaults.** `f()?` becoming `f().unwrap()` was in `stronger`; every Rust run now has it. A missed one is an error path no test runs, which line coverage can't show. A `?` on a formatter's write stays skipped as equivalent. Rust crates that use `?` get more mutants and their scores usually fall a little: on semver, humantime, clap_lex and httparse the defaults score moved by −1.7 to +0.4 points. No id changes; `stronger` and `extended` are unchanged. Java runs are unaffected. Already in the Rust edition's 0.2.0, so new only to readers coming from Java 0.5.0.

### Cards

Both editions, so a card stands alone without the source, for a person labelling misses and for an agent that reads the card alone. No id or score changes.

- **The `+` line is always the mutant's code.** Library and stream swaps, an `Optional`'s or a `Predicate`'s rule, a removed stream stage, a call left out, an array one value short, swapped arguments, a removed `throw`, `case` or `catch` type and a string or regex changed used to put words in the `+` slot, `+ (changed to endsWith)`, which read as code and hid which call changed. The change is now made where the source has it, the call on the mutant's own line when the statement has two of one name: `+ && ((value.endsWith("\"") && value.endsWith("\""))`. Where the source can't show it (a constant the source names, a parameter set to `null`, a method's whole body, a call whose arguments still run), the change goes on a line of its own that can't be taken for code: `= change  startsWith changed to endsWith (not shown in the source)`, `change: …` in the agent format. A statement the mutant takes out has a `-` and no `+`. The label under the caret names both sides, `startsWith changed to endsWith`, `>= changed to >`. A plugin's `Mutator.Words` has a fourth part, `written`, the changed call as a template on the call's parts (`{receiver}.allMatch({args})`), for its cards to show the code; a rules file's rewrites fill it in. The built-in rules' `changed:` now reads `{name} changed to {to}`. **Breaking** for a Java plugin that builds `Words` by its canonical constructor (the three-argument one stays).
- **A forced condition reads as its value.** `!true` is `false` and `!false` is `true`, the rest of the line as written: `+ return (false && next.getFirst().opcode() == Opcode.ATHROW)`. A condition with a constant javac wrote in (`count >= 50` for `count >= BULK_FROM`) is found by the name in the number's place.
- **The whole statement shows when it spans lines.** The `-` and `+` lines run from the statement's first line to its last (Java: the lines before javac's that don't end a statement, and on to the one that does; Rust: the syntax tree), cut to six lines around the change with `…`, in the full card, `explain`, the agent format and `also` lines.
- **`explain` on a skipped mutant says "skipped" once:** `SKIPPED  user: …`, not `SKIPPED  skipped: user: …` (Java; the Rust edition's `skipped: unviable: …` too).
- **Suggested tests use the source's names.** A parameter is named as the class file names it (MethodParameters, else the LocalVariableTable, which `-g` writes and Gradle and Maven compile with by default), so a boxed `Integer chosen` no longer gives `sizeWithArg01…`; a constructor's arguments are named by its parameters (`isFreeWithTotal0ForFreeFrom50`, was `…ForDelivery50`). An input at a value the source names says it the source's way and names the test: a constant the test can use goes in the call with its value in the comment (`discountAtFreeFrom`, `Discounts.discount(Discounts.FREE_FROM)) // FREE_FROM = 100 · …`); one it can't (private) stays a number and the comment says whose (`// 50 is Discounts's BULK_FROM · …`); a field the constructor sets from an argument, passed the same value as the parameter it's compared with, is one local passed to both (`int freeFrom = 50; … new Delivery(freeFrom).isFree(freeFrom)`). Rust: a `const` in the function's file, and a field of the receiver of the parameter's type; its value is now tried among the inputs too. Kotlin's suggestions are unchanged. The events' `suggestion` gains `named` and each receiver's `parameters`.
- **A card's headline is plain words with its code bold (Java),** as the Rust edition's is; the words were bold up to the first code term and plain after it.
- **Rust: covering tests in the Java edition's order,** the function's own test module first, then by name.
- **A removed part that still runs is shown as what's left (Java).** Removing a call or an assignment leaves the receiver and arguments Java works out first, so the `+` line now shows them as statements, `- out.append(System.lineSeparator());` / `+ System.lineSeparator();`, `+ count++;`, `+ next();` for `total = (next());`; the `= change … (not shown in the source)` line is kept only when what's left can't be written as a statement (`prefix + next()`). Rust's cards already show the source the mutant compiles.
- **Card rendering fixes (Java).** A conditional's arm on its own line (`low :`, `1 :`) is part of the statement, not a label; a line that starts with a closed comment (`/* x */ code`) has its code, and a line that ends a comment (`… */`) doesn't; a receiver kept alone (`raw\n    .strip()`) takes its line break, not a lone `;` line; an empty `--only` id is refused, saying what's wrong (both editions; Rust's ran every mutant).
- **A name a card uses says what it is and what it was.** A suggestion first tries the receiver as a covering test makes it, read from the test's source (`new Delivery(50)`; Rust: `Delivery::new(50)` in the test module beside the function), and when that call tells the mutant apart the test makes it the same way: `var delivery = new Delivery(50); // as DeliveryTest#smallOrdersPayForDelivery builds it`, `assertTrue(delivery.isFree(50)); // total == freeFrom · today true · with this mutant false`. The evidence says what the name is and its value: `inputs seen: total = 20, 80, with freeFrom = 50 (Delivery's field, from new Delivery(50))` and `none has total == freeFrom`; otherwise `the edge is FREE_FROM (Discounts.FREE_FROM = 100)` or `the edge is freeFrom (Delivery's field, set by its constructor)`. Events gain `suggestion.builtBy` and `suggestion.named.meaning` (Java).
- **A `char` reads as a character (Java).** Where javac writes `'/'` as `47`, a card now writes `'/'` again, by the type: an argument a `char` parameter takes, or the character `indexOf`, `lastIndexOf` and `Character`'s methods take as an `int` (`internal.lastIndexOf('/') + 1`, was `lastIndexOf(47)`); a number compared with, or added to, a `char` (`no test checks kind() at c == 'a'`); a `char` parameter's inputs seen (`'A', 'z' (none is 'a')`); and what a `char` method returns (`returns '\0' instead`). An `int` stays a number. Suggested tests already asserted a `char` as one, and don't take `char` arguments. The Rust edition's cards read their values from the source already. No id or score changes.

### Fixes

*From the real-project runs:*

- **Kotlin Multiplatform modules run, through their JVM target (Gradle).** A module with `kotlin("multiplatform")` and a `jvm()` or `jvm("desktop")` target was refused as having "no Java code", as mutastic read only modules with the `java` plugin. It now reads the target's compilations (common code included) and runs its tests (`desktopTest`, `jvmTest`); with one JVM target that's the default suite, with more `--suite` names one. A file named `Board.common.kt` is shown as itself, not as kotlinc's class `Board_commonKt.java` with no source. The Gradle plugin still needs the `java` plugin.

- **JUnit 4 tests run without the JUnit Platform, and a module with no JUnit at all runs.** A build that runs JUnit 4 with its own runner (Gradle's `useJUnit()`, which `kotlin("test")` picks) had no platform on its test classpath, and the worker didn't start ("internal error: the test worker didn't start"). mutastic now brings the platform, and JUnit 4's (vintage) engine for it, as it does TestNG's. A module with no tests and no JUnit, which crashed the same way after every mutant was reported not covered, now finishes.

- **A library finds the module's resources, as in the build (Gradle and Maven).** A library on the test classpath that looks a resource up with its own class loader (`ResourceBundle.getBundle(name, locale)`, say) couldn't see the module's own resources, so tests that pass in the build failed under mutastic ("the tests fail without any mutant"). The module's resources (not its classes, nor `META-INF`) are now on the classpath beside its libraries, where the build has them.

- **A class whose static initialiser failed no longer stops the worker.** A test that expects a class to fail to initialise (or a red baseline with one) ended the worker with `class redefinition failed: invalid class`; such a class can't run again, so it's left as it is.

- **Kotlin: two more of the compilers' values skipped as generated (`all` only).** A `$default` call with more than 32 arguments left out has a mask for every 32; only the last was read, so the stand-ins and masks before it were mutated. And the `$changed` `0` passed to a composable lambda's `invoke` is the Compose compiler's. Fewer mutants on such code, all of them noise; no id changes for the rest.

- **A ghosts run that finds none says so** (`--experimental=ghosts`): the summary's Ghosts row reads `none found`, and `run-finished` has `"ghosts": []`.

- **A module's directory runs its build's wrapper and its own tasks (Gradle and Maven).** Pointed at a module of a multi-project build (`mutastic run app/core`), mutastic looked for `gradlew` only in that directory, so it fell back to `gradle` on the `PATH`, or failed without one, and ran the root project's `:mutasticSetup`. The wrapper is now the nearest one up to the top of the build (its settings file; `.mvn` for Maven), and the task is the module's.

- **Each command's `--help` and reference page say how it exits,** beside the statuses every command shares.

- **The agent format's `rules:` line quotes the verify command alone:** "a fix counts when `mutastic verify <id>` passes", where "passes" was inside the command an agent would copy.

- **`run --only=a,b,c` says every mutant it was asked for (Java).** It printed only the first one's fate, and for an agent only its card; the rest were run and never shown. Each one's fate now ends the run, with the card of each one missed. The events' `only` still holds the first id; `passed` was already whether every one is caught. The Rust edition shows every one already. No id or score changes.

- **An `Integer` edge clamp is skipped as equivalent, as the `int` one is (Java).** `chosen > 3 ? chosen : 3` with `chosen` an `Integer` (or `Long`) unboxed on each read gives the same value either side of the edge, and is now skipped as `equivalent: both sides give the same value at the edge`. Scores on such code go up slightly; no id changes.

- **Windows: Maven builds run, and output reads as it does elsewhere.** On Windows mutastic couldn't start Maven: it ran `mvnw` or `mvn`, the scripts for other systems, and now runs `mvnw.cmd`, or `mvn.cmd` from the PATH. A Gradle build without a wrapper ran only where `gradle` was an `.exe`; `gradle.bat` runs too. Cards, report paths and the commands they offer wrote `\` (`src\main\java\...\Pricing.java:17`) and quoted a Windows path (`'C:\code\app'`); paths below the project are now written with `/` on every platform and a Windows path isn't quoted. Lines end with a plain newline everywhere, where Windows mixed in CRLF, and output to a pipe or file is UTF-8, where Windows wrote its legacy code page and lost `✓` and `✗`. No id or score changes. The CLI's end-to-end tests on the cards, the pricing sample and the Maven plugin now run on Windows on every change.

- **A module whose code another module of the build finds by name runs (Gradle and Maven).** When one module looked up another's class by name (`Class.forName("...Impl")`, the usual way to pick an optional implementation), the lookup failed under mutastic though it passed in the build, and the run stopped with "the tests fail without any mutant". The build's other modules are now loaded with the module's own code, as the build's one classpath has them. A library from outside the build is still loaded as a library. Scores don't change where runs already worked; no id changes.

- **`explain`, `verify` and `ignore` say when the word in the id's place isn't a mutant id.** A directory there was taken as the id, and the answer was that the current directory isn't a Gradle or Maven build. Now it says a mutant id comes first, with the command.

- **`next` counts as a run for `explain`.** After `mutastic next`, `explain`'s last-run row said "none yet". next's pass over every mutant is now kept as the last run, as `run`'s is; its later passes still rerun only what was left.

- **Cards name a class literal, and a string constant, as the source writes them.** A card on `if (!KyberParameterSpec.class.isAssignableFrom(spec))` said `ClassOrInterfaceDesc[KyberParameterSpec].isAssignableFrom(spec)`, and one on `msg.equals(Protocol.EXIT_CODE)` said `msg.equals("{}")`, the value javac writes in. Cards now say `KyberParameterSpec.class`, `int.class` (not `Integer.TYPE`) and `msg.equals(Protocol.EXIT_CODE)`; a string stays its value where the statement doesn't show which name it is. A number named by a constant still shows its value, as the input to test with. Scores and ids don't change.

- **`next` leaves a proposed mutant to a person.** A mutant proposed for ignoring (`ignore --propose`) was handed back by `next` as if no one had looked at it, and `next` never exited 0 while one waited. It's now left out of the queue and counted ("2 mutants proposed for ignoring, waiting on a person to accept them"), `next-finished` has a `waiting` count, and when only proposals are left `next` exits 0. For a line with several mutants, the agent's `decline:` line names each of them, not only the first.

- **A word a command doesn't take is refused (exit 2).** `mutastic run samples/pricing explain m855c8e3c` used to drop the extra words and run the module. Now it says "run doesn't take explain m855c8e3c: mutastic run [DIR] [:MODULE] [OPTIONS]", as the Rust edition does. A script that passed stray words now stops with exit 2.

- **`mutastic next`: the missed mutants one at a time.** It shows the missed line most worth a test first, in the order a run ranks them, with why it's first; `--top=N` shows a few, spread over the code; for an agent, one task and to run `next` again. While the code under test is unchanged, it reruns only what the last `next` left uncaught. Exits 1 while something is queued. JSON Lines gains `next-item` and `next-finished` events.

- **`mutastic baseline`: new misses told from old.** It records every mutant no test catches in `.mutastic/baseline.yaml`, once; `--prune` drops those caught now. With a baseline, the gate holds only misses that aren't in it (and holds a task that adds to it), `next` offers new misses first, and `run` says how many of the baseline's are caught now. Scores don't change.

- **The gate holds a task that takes a failing test away.** A test that failed at a gate check and is gone at a later one holds the gate, by name, for the rest of the task: deleting the test that said a task was wrong used to pass it. A test moved to another class (same name) or renamed (same body) still counts. The gate's message for a failing test the task added also tells the agent to leave it in place.

- **Finding the mutants in a big class is faster.** To check that a mutant will load, mutastic verified the whole mutated class, every method of it, once per mutant. It now verifies the mutated method alone when the class as it is verifies, and the whole class only when it doesn't. The verdict is the same: mutastic's own classes gave the same verdict and message for all 43,427 such mutants, in 6.2 s instead of 15.7 s. No result or id changes.

- **A run leaves nothing behind.** A worker killed mid-test, by a mutant that never finishes, left that test's temporary files in the system's temporary directory for good, and a process a test started and left running outlived its worker however the worker ended. Each worker now has a temporary directory of its own, deleted when it goes, and what its tests started ends with it. A project that names `-Djava.io.tmpdir` in its test JVM arguments keeps its own directory. A test that counts on finding something already in the temporary directory (a download cached between runs, a file shared with another process) needs to name one that way. A process that has detached itself, such as a Gradle daemon or a Docker container, is left alone.

- **Each mutant now costs one class redefinition fewer.** A mutant stays in place until the next one, which goes straight in if it's of the same class, so nothing is redefined back only to be replaced. A redefinition takes tens of milliseconds, more than most tests that catch a mutant: on mutastic's own engine the median mutant went from 212 ms to 145 ms, and no result changed.

- **A worker that has slowed is replaced.** A class redefinition walks every class a worker has loaded, so on a project whose tests sit in its production packages, a worker grew slower with every test it ran: on mutastic's own engine a redefinition went from about 25 ms on a new worker to between half a second and three seconds on an old one. A worker now measures what its redefinitions lose, and past a budget the next mutant goes to a new worker, which first initialises the classes the coverage pass loaded, in the same order, so a mutant in a static initialiser can't break a class for the mutants after it. On mutastic's own engine the run took about half the time, and no caught mutant became missed.

- **`ignore --prune` keeps a reviewed ignore on a mutant the run's operators leave out.** An ignore made under `stronger` counted as stale in a run under `defaults`, which reported it and let `--prune` delete it. An ignore is now stale only when its mutant no longer exists under any operator, or its line has changed.

- **A score is compared only with a run under the same operators.** A whole run under another `--operators` used to report a previous score and changed results from a different set. `last-run.tsv` now records the operators, so such a run has no comparison. A record from an earlier version still loads, and its next run has no comparison either, as its set can't be known.

- **The gate leaves room for a wrong spec.** When a test the task added fails, the gate now says to make it pass unless the spec or plan is what's wrong, in which case to say so and stop.

- **A hooked gate outside a project mutastic reads says nothing.** With `--hook`, `gate open` and `gate check` in a directory that isn't a build mutastic reads now exit 0 with no output, as a hook with nothing to gate should, so agent plugins no longer need to know which builds mutastic reads. Run by a person, the command still says the directory isn't a build it reads.

- **A hooked gate doesn't ask the agent to run `gate check`.** With `--hook`, `gate open` used to end with "`mutastic gate check` says when it's done", so an agent ran the check itself before stopping and the Stop hook ran the same mutants again. It now says the gate checks the lines when the agent finishes. Without `--hook` it's unchanged.

- **In Codex's sandbox, a build it refused says so.** Codex runs an agent's commands in a sandbox where a Gradle or Maven build can't write its caches in the home directory or use the network, and the build failed with a stack trace that never said why. When `CODEX_SANDBOX` is set and the build's failure is the sandbox's refusal ("Operation not permitted"), mutastic now adds a line saying so, and that a rule (`prefix_rule(pattern = ["mutastic"], decision = "allow")` in `~/.codex/rules/default.rules`) lets Codex run it outside the sandbox. Nothing changes outside Codex's sandbox. Docs: a Codex page under Coding agents; the mutastic plugin now works in Codex too (tasticlabs marketplace).

- **One proposed rewrite no longer stops a run.** With `--operators=proposed`, a class with a method where `?:` joins two of the project's own types (`given != null ? given : new Flat()`) ended the run with `internal error: Could not resolve class …` for every proposal of that class. Proposals are now compared without needing the project's types, and a proposal that still can't be judged is skipped as `unviable: it couldn't be judged: …` while the rest of the run goes on. A change to a nested class filed under its outer class now says which folder it goes in (`.mutastic/proposed/<Outer>$<Nested>/`).

- **A plugin's skip reason without a kind no longer breaks `--format=jsonl`.** `skipKind` was the reason's text before its first colon, which failed for a reason with no colon and could be a kind the events schema doesn't list. It's now left out unless the reason starts with `generated`, `equivalent`, `unviable`, `arid` or `user`; `statusReason` still has the reason.

- **A fresh multi-module Gradle checkout runs (Gradle only).** On a checkout never built, or after `./gradlew clean`, a module whose tests use another module's jar (any build using `java-library`) stopped with "the tests fail without any mutant", because mutastic built only that module's classes and the other module's jar didn't exist yet. mutastic now builds everything the test task's classpath names, as the test task would. The Gradle plugin's `mutasticWriteSetup` also ran the module's whole test task before writing its setup; it no longer does, so a plugin run starts sooner. Maven builds weren't affected. Scores don't change.

- **A suggested test no longer says a mutant "doesn't return" when it does.** A suggestion's call had 200 ms before mutastic took it as one that never returns, so on a busy machine a card could say `// today 1 · with this mutant doesn't return` for a mutant that returns at once, and the same run could give a different card on another machine. A call now has 2 s; one that really loops costs that once.

- **A card lists its covering tests in the same order every run.** "Covered by" listed the tests in the order they ran, fastest first, and timings move with the machine's load, so the same card could list them differently from one run to the next. Now the mutant's own test class's tests (`PricingTest`'s for `Pricing`) come first, then the rest by name, in the cards, `coveredBy` in the events and report files, and `succeedingTests`. A suggested test goes into the first listed test's class, which was the fastest test's: it's now the mutant's own test class when one of its tests reaches the mutant. The tests still run fastest first. Card text only: no score or id changes.

### New operators

- `mutastic/bitwise` (stronger): `&`, `|` and `^` each become the other two, `<<` and `>>` swap, and so do `>>` and `>>>`, on `int`, `long` and `boolean`, as the Rust edition's operator of the same id does. `~x` (javac's `x ^ -1`) isn't changed, and `&=`/`|=` never become `^=`. `defaults` runs are unchanged; `stronger` runs on code that uses bitwise operators get more mutants, and their scores move. No existing id changes.

- `mutastic/return-value` (defaults): replaces `mutastic/return-default`; see Scoring changes.
- `mutastic/remove-discarded-call` (defaults): a call whose result is thrown away, removed; `Objects.requireNonNull(x);` is skipped as arid.
- `mutastic/comparison` (stronger): a comparison becomes the one that differs from it on one side of its edge (`<` to `!=`, `==` to `<=` and `>=`); only on numbers, `NaN`-safe for `float` and `double`, and a size or length changed only below zero is skipped as equivalent.
- `mutastic/other-comparison` (stronger): every other change of a comparison, and any condition's opposite; replaces `mutastic/negate-conditional`.
- `mutastic/logical-connector` (defaults): `&&` to `||` and back, in a condition of two tests; conditions of three or more, nested `if`s and a test that reads what a pattern binds are left alone.
- `mutastic/remove-assignment` (defaults): an assignment to a variable that already has a value, removed; a first assignment isn't changed, code without its table of local variables gets none, and one nothing reads is skipped as equivalent.
- `mutastic/remove-field-write` (stronger): a write to a field, removed; constructors and static initialisers are left alone, and a plain setter is skipped as arid.
- `library/swap-string` (defaults): `startsWith`/`endsWith`, `toUpperCase`/`toLowerCase`, `stripLeading`/`stripTrailing`.
- `library/swap-index-of` (stronger): `indexOf`/`lastIndexOf`; a search only tested for being there is skipped as equivalent, and one with a starting point is left alone.
- `library/swap-math` (defaults): `Math.min`/`max`, `Math.floor`/`ceil`; rounding a value just converted from a whole number is skipped as equivalent.
- `mutastic/omit-call` (stronger): a call that only wraps a value, left out, `text.trim()` to `text`; `String`'s and the string builders' methods, `Math.abs`, and the project's own non-generic methods.
- `mutastic/swap-arguments` (stronger): a call's last two arguments of one type, swapped; not `Object`, and a commutative call or one value twice is skipped as equivalent.
- `mutastic/null-parameter` (stronger): a parameter holding an object, `null` from the start of the method; one nothing reads is skipped as equivalent, and code without its table of variables has none.
- `mutastic/drop-vararg` (stronger): an array built for a call on the spot, without its last element.
- `library/omit-argument` (stronger): curated JDK overloads without their last argument, `indexOf(x, from)` to `indexOf(x)`.
- `mutastic/flip-increment` (stronger): a local variable's step the other way, `i++` to `i--`, `i += 2` to `i -= 2`; a step nothing reads is skipped as equivalent, and an enhanced `for`'s own index is left alone.
- `mutastic/remove-negation` (stronger): a minus sign removed, `-x` to `x`, on all four number types.
- `mutastic/other-arithmetic` (stronger): every other change among `+`, `-`, `*`, `/` and `%`, on `int`, `long`, `float` and `double`, beyond what `mutastic/math` and `mutastic/arithmetic-replacement` make.
- `mutastic/keep-operand` (stronger): `a + b` to `a` and to `b`, both still worked out; a constant beside a variable isn't what's kept.
- `mutastic/constant-value` (stronger): a number, a `static final`'s included, to one next to it and to `0`, `1` and `-1`; booleans and javac's own numbers are left alone, and array sizes, capacities, waits, a `split`'s limit and a variable's first `0` or `1` are skipped as arid.
- `library/unchecked-arithmetic` (stronger): `Math.addExact`, `subtractExact`, `multiplyExact`, `incrementExact`, `decrementExact`, `negateExact` and `toIntExact`, and `StrictMath`'s, as the plain operation, which overflows instead of throwing.
- `stream/remove-other-stage` (extended): `peek`, and `map` on a primitive stream, removed; `Stream.map` is left alone, as it can change what the stream holds.
- `predicate/swap-connector` (extended): `Predicate.and` and `or`, on `BiPredicate` and the primitive predicates too.
- `predicate/remove-negate` (extended): a predicate's `negate()`, removed.
- `predicate/remove-not` (extended): `Predicate.not(p)` to `p`.
- `optional/swap-present` (extended): `isPresent` and `isEmpty`; skipped as unviable in a class file older than Java 11.
- `optional/empty` (extended): `Optional.of(x)` and `ofNullable(x)` to `Optional.empty()`, and the primitive forms.
- `optional/remove-filter` (extended): `Optional.filter`, removed.
- `optional/always-default` (extended): `opt.orElse(x)` to `x`.
- `optional/no-default` (extended): `opt.orElse(x)` and `orElseGet(s)` to `opt.get()`.
- Rules files can now use the `keep argument` rewrite and a `since:` field naming the Java release a rewrite needs.
- `mutastic/remove-switch-arm` (extended): one `case` of a `switch` sent to the default, per label; where the only default is javac's own, a value that reached the arm throws, and the card says so.
- `mutastic/replace-body` (extended): a method's whole body, to returning one value or nothing; a `boolean` gets both values. Meant as a first pass run alone, to find methods no test notices.
- `mutastic/drop-catch-type` (extended, experimental): one type of a multi-catch, no longer caught.
- `mutastic/remove-throw` (extended, experimental): a `throw` removed, and the code carries on; with nothing after it, skipped as unviable; javac's own throws are left alone, and a failed `assert`'s is skipped as arid.
- `mutastic/string-constant` (stronger): a string in the code to `""`, and an empty one to a word; what goes into a log call, an exception or a failed check is skipped as arid. Expect misses on text a person reads.
- `mutastic/regex-constant` (extended): a regular expression going straight into `Pattern.compile`, `matches`, `split`, `replaceAll` or `replaceFirst`, with an anchor gone, a class or `\d` the other way round, or a quantifier gone; it also runs in constructors and static initialisers.
- Plugins shipped in the distribution's `plugins` directory, off until listed in `mutastic.yaml` and chosen, all in `extended`:
- `annotations/remove-validation`, `annotations/remove-security` (`mutastic-plugins.jar`): one annotation removed. Each mutant loads every class anew, and a survivor costs a run of every test.
- `kotlin/empty-return` (`mutastic-plugins.jar`): an empty range or sequence, in what kotlinc compiled.
- `proposed/rewrite` (`mutastic-plugins.jar`): rewrites kept as Java files in `.mutastic/proposed`, compiled and checked; mutastic calls no model.
- `bigdecimal/swap-arithmetic`, `bigdecimal/swap-scaling`, `bigdecimal/remove-step` (`bigdecimal.yaml`).
- `reactive/remove-stage`, `reactive/remove-recovery`, `reactive/swap-match`, `reactive/swap-order` (`reactive.yaml`).
- New in the plugin API: `ClassMutator`, for a change to a class as a whole.

### Compatibility and distribution

- **A Compatibility page** in the reference says what stays the same between releases: mutant ids (and the one way an id can come to mean another mutant, when its method's code changes), scores (a patch release never moves one; a minor release that does says how here), and the plugin API.
- **`@Internal`** marks what's public in `mutastic-api` only for mutastic's own modules: the result model, build tools, `Policy`, `BuiltIn` and a few methods of `Mutant` and `Project`. A plugin written against `Mutator`, `ClassMutator` and `Filter` is unaffected; one that named a `BuiltIn` constant should name the operator by its id. `BuiltIn.RETURN_DEFAULT` and `NEGATE_CONDITIONAL` are gone since 0.5.0 (see Scoring changes).
- **`io.mutastic:mutastic-plugins`** is on Maven Central, beside the copy in the distribution's `plugins` directory.
- **The Gradle plugin works with the configuration cache and Isolated Projects,** and says so on the Plugin Portal.
- **JSON Schemas** for the `--format=jsonl` events, `mutastic.yaml` and rules files, at https://docs.mutastic.io/schemas/events/v1.json, `/schemas/mutastic-yaml/v1.json` and `/schemas/rules/v1.json`. The build checks every event against the events schema and holds it to the last release's, and the reference has a Schemas and stability page saying what may change in v1.

- **Events say which edition wrote them (`mutastic.events/v1`).** Every event may carry `edition`, `java` or `rust`, and one without is the Java edition's. The Java edition writes `edition` on `run-started` and `run-finished`, and `toolchain` (`{"name": "jdk", "version": "17"}`) beside `jdk`. `jdk`, a mutation's `bytecode`, and a missed mutant's `ignore` and `propose` are now required only of the Java edition's events, which every event without an `edition` is. The Java edition still writes them all, so a reader of Java output finds nothing missing. The schema adds `RuntimeError` as a status and documents the Rust edition's own fields: `nearUnsafe`, `notMutated.unsafe`, `runtimeError`, `profile`, `unsafe`, `location.column` and `build`. A reader that treats an unknown status as neither caught nor missed, as the schemas page asks, needs no change. Superseded in part by Breaking changes: `edition` is on every event, and `bytecode` is gone.

### Running

- **`--shard=N/M` splits one run across CI jobs, and `--from-shards=DIR` puts it back together**. `mutastic run --shard=2/4` runs only the second of four shares of the module's mutants, dealt by each mutant's id, so every mutant is in exactly one share and the shares come out about the same size. Each share keeps what putting it back together needs in one folder, `shares/N-of-M/` in the module's work directory: its events (as `--format=jsonl` writes them, whatever the format shown) and its own run record, so a share compares only with the same share's last run. Run shares with `--threshold=0`; then `mutastic run --from-shards=DIR --threshold=70`, with every share's folder somewhere under `DIR`, runs no mutants: it finds them in the build as a whole run would, takes each result from its share, and is the whole run, with the same summary and cards in every format, the same report files, the threshold checked on the whole score, and the whole run's record for `explain`, `next` and the next run to compare with. It refuses (exit status 2, saying what's wrong) a share missing or there twice, one that didn't finish, shares split differently, a mutant in two shares, and shares run by another mutastic or on other code than each other or the build. The events gain `share` on a share's `run-started`, a `suggestion` on a mutant that has one, and `workers`, `workersStarted` and `coverageMs` on `run-finished`. The docs guide "Changed lines, other tests and suites" has a GitHub Actions example. No scoring change: a run without `--shard` is as before.

### Agent output

Both editions. No id or score changes.

- **An agent's proposal carries its kind and a reduction.** The agent format's `or propose` line is now `mutastic ignore <id> --propose --as=<equivalent|unreachable|…> --reason="Differs only when <case>. That can't happen because <why>." <project>`, and the `decline:` line asks for the kind and the two sentences. `ignore --propose --as=equivalent` (or `unreachable`) refuses a reason without a "because", saying what's wanted; any proposal that still has the template's `<case>` or `<why>` is refused; other kinds, and a person's own `ignore`, keep a free reason. **Breaking** for a script that ran the old `or propose` command as it was printed.
- **mutastic's own equivalent skips say why in the same shape** where there's a case to name: a size or length compared with zero, a test of an `||` the next one covers, a whole number rounded, an anchor on a whole-text match, `@NotNull` on a primitive (Java); a `?` on a formatter's error (Rust). Others (nothing reads the value, both sides agree at the edge) keep their words.
- **Mutants no test reaches list their ids in the agent format,** by line, each with its `or propose` command (several on a line share one, `for each of …`).
- **The gate names every mutant that holds it,** by id, those no test reaches too (`no test reaches: m04b90863, m304d2733`), says a proposed mutant is "proposed, waiting on a person" rather than missed, and leaves proposed ones out of its cards. It used to say "2 mutants … are missed", showing the two already proposed, while two not-covered mutants without ids were what held it.
- **The gate hands over at once when an agent sent back changes nothing.** A hooked check keeps the working tree's hash when it sends the agent back; if the next check finds it unchanged, the agent may stop and the person is told what's left, where the agent was sent back for every try. A change, even one that doesn't close the gap, is still a try.
- **The gate doesn't tell an agent to make a test that was there before the task pass.** When tests fail before any mutant, it names those that were in their class's source when the task started, and tells the agent to find out whether the change or the test is wrong, and to ask.
- **A pass names an existing test the task weakened.** When the gate passes or hands over, it names the test files that were there when the task started and lost an assertion or expectation, for the person to check: a test made to fit a wrong change catches every mutant, so the score can't show it.
- **The Equivalent mutants page has "How to argue a mutant can't be caught"**, equivalent and unreachable examples each with what pins its "because", and Rust tabs throughout; the mutastic plugin's skill teaches the two-line reason.

### Installing

- **One line installs `mutastic` for Java, Kotlin and Rust projects.** `curl -fsSL https://mutastic.io/install.sh | sh` installs the native `mutastic` (mutastic-rs), which runs Rust crates itself and, the first time it runs in a Maven or Gradle project, fetches the Java edition, checks it against its release's checksums and keeps it in `~/.mutastic`. The Java edition needs JDK 25 only for Java projects. `install.sh` in the Java repository now runs that installer. `MUTASTIC_VERSION` picks the Java edition's release when `mutastic` runs, rather than at install. The Gradle and Maven plugins are unchanged.
- **The native `mutastic` runs the newest Java edition patch of its own minor version.** The editions release each minor version together and either may ship a patch alone, so `mutastic 1.0.0` runs the newest Java 1.0.x, fetching it if it isn't kept. It looks the releases up at most once a day, runs the newest matching release it has kept when they can't be reached, and when a newer patch can't be fetched yet (its GitHub release is published before its CLI is attached) runs the kept one of the same minor, saying why. `MUTASTIC_VERSION` still picks one exactly.
- **Windows (Rust edition's builds):** x64 and Arm64 zips with each release; `irm https://mutastic.io/install.ps1 | iex` in PowerShell installs one and puts it on the user `Path`; `install.sh` in Git Bash, MSYS2 or Cygwin installs the same build; each release has a Scoop manifest, and `cargo binstall --git https://github.com/mutastic/mutastic-rs mutastic` works. In a Maven or Gradle project on Windows, `mutastic` fetches and runs the Java edition.

### Docs

- **docs.mutastic.io shows both editions,** with a Java | Rust switch at the top of the sidebar, the concepts both share written once, a page for each operator family (each operator with the bug it stands for, a real card and the test that catches it), a Coding agents section (the Claude Code and Codex plugins, how the gate decides, opencode and other agents), a Running in CI guide for GitHub Actions and GitLab CI, and a Compatibility page.

### Experimental

- **`--kill-matrix` (for measuring, slower).** A run with it runs every test that reaches a caught mutant, not only until the first fails, and JSON Lines names each catcher in `caughtByAll`. It's what the replay of `next`'s order against random order reads (`tools/replay.py`). Scores don't change.

- **Ghosts (`--experimental=ghosts`, or `MUTASTIC_EXPERIMENTAL=ghosts`).** Where most writes to a bean's fields fire a `PropertyChangeSupport` property change and one doesn't, mutastic inserts the call the others make after that write and runs the tests that reach it. A ghost that no test notices is listed apart, as a question with both answers; one a test notices names that test. Ghosts are never in the score, the counts or the gate, and `--format=jsonl` lists them under `ghosts` in `run-finished`, each with `"experimental": true`.

## 0.5.0 (5 Nov 2026)

### The gate: a coding agent's task held to its mutants

```sh
mutastic gate open     # where the task starts
mutastic gate check    # is it done?
mutastic gate close
```

`gate check` passes when every mutant on the lines changed since `gate open` is caught, or proposed for a person to ignore. An ignore the task made itself holds it, and so does a test deleted or disabled. Until then it answers with the cards, in the agent format, so the agent can carry on. With `--hook=claude` it reads and answers as a Claude Code hook. After `--tries`, it lets the agent stop and tells the person what's left, rather than sending the agent round for ever.

The [tasticlabs plugin](https://github.com/tasticlabs/plugins) wires it into Claude Code for you:

```
/plugin marketplace add tasticlabs/plugins
/plugin install mutastic@tasticlabs
```

### An agent proposes an ignore, and a person accepts it

Whether a mutant's case can happen in practice is a person's call. `mutastic ignore ID --propose --reason="…"` records the proposal in `.mutastic/proposals.yaml`, where the mutant still counts as missed. `ignore --accept ID` makes it an ignore, with its reason and kind. `--remove` withdraws it. Cards offer agents `or propose` where people get `or ignore`, and the event stream has both.

### Advice an agent can act on

Each card's claim is now checked against Java itself: it's written into the source, compiled, and run against the mutant on a grid of inputs. That check found, and this release fixes:

- **A removed condition on a boolean** read backwards for the first test of an `||`. It's now read from the jump itself.
- **A condition split over lines** had the wrong line rewritten. `wide < 1L` was rewritten as if it were `wide < 1`. A line is now rewritten where the mutated comparison is, by whole words.
- **Cards quote the source more exactly:** a class's own statics bare, and comparisons by their names.

Also:

- **`verify` on a skipped mutant** says it's skipped, and why, and passes. It no longer says no test reaches it.
- **A flaky test's advice goes by why it's flaky.** The test makes its own flakiness (an unseeded `Random`, the clock): fix it. It comes from outside the code: leave it, and tell the person. It's in the code under test: fix it through its seam. Unknown: say so. In evals, telling an agent to "fix it first" for a slow shared service led to retries in every run, hiding the timeout. JSON Lines has the evidence as `flakyEvidence`.
- **When a JDK service the project provides** (a `System.LoggerFinder` in `META-INF/services`) is why the tests fail under mutastic, the failure says so.

### Fewer false misses

- **JDK services are found as in the build.** The worker's system class loader now finds the project's classes. So a test that installs a `LoggerFinder` or `FileSystemProvider` passes under mutastic as it does in Gradle. This works with `-javaagent` jars such as JaCoCo's and Mockito's, but not in framework mode.
- **New equivalent skips:**
  - a clamp to a constant at its edge (`fee < 10 ? 10 : fee`, `if (x < 2) x = 2;`), for `int` and `long`;
  - the first test of an `||` whose case the next test already covers (`inCredit(balance) || balance > -1000`).

  Both are checked on every input by the truth check.
- **`--tests-from` through another module.** Say `app`'s tests run over `core`'s mutants, and `app` also uses `web`, which uses `core`. `web` is now loaded beside the mutated classes, not as a library, so it uses them instead of failing to find them.
- **The summary** counts the workers that ran, so a small run no longer says "-1 replaced".

### Docs

- **[Equivalent mutants](https://docs.mutastic.io/concepts/equivalence/):** what they are, which mutastic skips, and how it knows.
- **[Flaky tests](https://docs.mutastic.io/concepts/flaky-tests/):** what mutastic says, and why.
- **Reference pages** for `gate open`, `gate check` and `gate close`, and proposals in the configuration guide.

Compare: https://github.com/mutastic/mutastic/compare/v0.4.0...v0.5.0

## 0.4.0 (4 Nov 2026)

### Install in one line

```sh
curl -fsSL https://mutastic.io/install.sh | sh
```

It installs into `~/.mutastic`, links `mutastic` into `~/.local/bin`, and updates when run again. This release is the first with `SHA256SUMS`, which the script checks the download against. The Gradle plugin now resolves from the Gradle Plugin Portal with no `pluginManagement` setup.

### Scores change in this release

Expect your score to move, for these reasons, not because your tests changed:

- **`negate-conditional` left the defaults** and is now only in `stronger`. A test can catch a negated condition while leaving both "always true" and "always false" uncaught. The reverse never happens: a test that catches either of those catches the negation too. In the defaults, negate's catches only added to the score. PIT made the same change in 1.7.2. Scores usually fall: mutastic's own engine module went from 57.4% to 53.8%. `--operators=stronger` brings negate back.
- **Code that runs only while a class loads is now really tested.** A mutant in a method only a static initialiser calls used to be reported as missed, because the class was already loaded. It now runs in a fresh class loader, so those false misses become catches.
- **New default skips,** each recognised from the exact instructions involved:
  - `equivalent:` a boundary where both sides give the same value at the edge (`if (x > max) max = x;`, `a < b ? a : b`).
  - `arid:` arithmetic that only sets a collection's starting size (`new ArrayList<>(n * 2)`) or how long to wait (`Thread.sleep(s * 1000)`), and removing a `sleep`.

### Less to read, and more to act on

- **One card per line.** The missed mutants on one line share a card. It shows each distinct suggested test once, and its `done when` checks them all: `mutastic verify a,b`. Use `--each-mutant` (`eachMutant` in the build plugins) for a card per mutant.
- **`verify` takes several ids,** as do `--only=a,b` and `-Dmutastic.mutant=a,b`. It passes only when every one is caught.
- **Code no test reaches is listed once per method,** and the result adds a score for the code tests do reach (`coveredScore` in JSON Lines). The threshold still applies to the whole score.
- **Ranking and caps.** The agent format's ten entries come most valuable first: conditions and removals before arithmetic. People see at most seven cards a file, with the rest counted.
- **When not to write a test.** The agent format and JSON Lines now tell agents to say why, and ignore the mutant with that reason, when a mutant's case can't happen in practice or its change is one no caller can see.
- **`mutastic ignore --as=KIND`** records what sort of decision an ignore is: `equivalent`, `unreachable`, `telemetry`, `deprecated` or `accepted-risk`. The summary counts ignores by kind, and three or more ignores in one method suggest one `skip:` rule.

### Docs

- A **Plugin API** reference, generated from the API's own doc comments: https://docs.mutastic.io/reference/api/
- Shared cards, the covered-code score and ignore kinds are documented, with real output.

Compare: https://github.com/mutastic/mutastic/compare/v0.3.0...v0.4.0

## 0.3.0 (3 Nov 2026)

No notes were written for this release. Compare: https://github.com/mutastic/mutastic/compare/v0.2.0...v0.3.0

## 0.2.0 (2 Nov 2026)

No notes were written for this release. Compare: https://github.com/mutastic/mutastic/compare/v0.1.0...v0.2.0

## 0.1.0 (2 Nov 2026)

No notes were written for this release. Compare: https://github.com/mutastic/mutastic/commits/v0.1.0
