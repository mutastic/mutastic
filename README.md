# mutastic

*Find out whether your tests, or your agent's, actually check anything.*

mutastic is a mutation-testing tool for Java. It makes small changes to your compiled code, such as `>=` to `>`, and runs your tests against each one. A change that no test notices is a **missed mutant**: a place where your tests run the code but don't check what it does.

> **Status: early.** Five operators (boundaries, negated conditions, arithmetic, returned defaults, removed void calls), Gradle and Maven projects with JUnit Platform tests, parallel workers, suggested tests, flaky-test detection, and output for people, agents and tools (JSON, HTML and XML reports). Expect everything to change.

## Try it

You need JDK 25 to build and run mutastic. Your project can be on JDK 17 or later: its tests run on its own JDK.

```sh
./gradlew :mutastic-cli:installDist
./mutastic-cli/build/install/mutastic/bin/mutastic run samples/pricing
```

```
 Missed ────────────────────────────────────────────────────────────

 ❯ src/main/java/com/example/pricing/Pricing.java  1 missed in 1 method

 [✗ MISSED] no test checks discount() at total == 100
    ┌─ src/main/java/com/example/pricing/Pricing.java:17
    │
 16 │   public int discount(int total) {
 17 │     if (total >= 100) {
    │               ^^ changed to >
 18 │       return total / 10;
    │
    = mutant  - if (total >= 100) {
              + if (total > 100) {
    = note: every test calls discount() away from the boundary
            inputs seen: 50, 250 (none is 100)
            covered by PricingTest#bigOrdersGetTenPercentOff, PricingTest#smallOrdersPayFullPrice (all 2 covering tests still pass)
    = help: test discount() with total = 100 and assert the result

            // in PricingTest
            @Test
            void discountWithTotal100() {
              assertEquals(10, new Pricing().discount(100)); // today 10 · with this mutant 0
            }
            check today's value is the one you intend: the test pins what the code does now

    done when  mutastic verify m855c8e3c samples/pricing passes
    reproduce  mutastic run --only=m855c8e3c samples/pricing

   Classes  0 clean | 1 with misses (1)
   Mutants  ✓ 6 caught · ✗ 1 missed · 2 skipped (2 equivalent) (9)
  Worst at  Pricing.discount() 1 ✗
 Test runs  8 (21 without coverage selection)
  Start at  09:13:06
  Duration  1.3s (setup 546ms, coverage 204ms, mutants 544ms)
     Tests  3 on JDK 17 · 4 workers
   Reports  build/mutastic/mutation.json · build/mutastic/mutation.html
     Score  85.7%  threshold 80

 [PASS] 1 missed, above the threshold.
 Next: mutastic verify <id> samples/pricing · mutastic run --only=<id> samples/pricing · mutastic explain <id> samples/pricing
```

Add the suggested test (after checking 10 is the discount you mean for an order of exactly 100), then:

```sh
mutastic verify m855c8e3c samples/pricing
# mutastic: m855c8e3c is caught by com.example.pricing.PricingTest#discountWithTotal100
```

## Usage

```
mutastic run [dir] [:module] [--format=full|brief|concise|grouped|agent|jsonl] [--threshold=N] [--only=ID] [--since[=REF]] [--tests-from=:module|dependents]...
             [--confirm-catches] [--workers=N] [--color=auto|always|never]
             [--hyperlinks=auto|always|never] [--progress=auto|bar|counter|none] [--no-suggestions]
             [--report=json|html|xml|none[:path]]... [--vocabulary=plain|classic]
mutastic verify ID [dir] [:module]
mutastic explain ID [dir] [:module]
mutastic ignore ID [dir] [:module] --reason="…" | mutastic ignore --prune [dir] [:module]
```

| Exit code | Meaning |
|---|---|
| 0 | The score reaches the threshold (80 by default); for `verify`, the mutant is caught |
| 1 | It doesn't |
| 2 | A usage error, or a build mutastic couldn't read |
| 3 | The tests fail without any mutant (a flaky test that passes when run again doesn't count) |
| 4 | mutastic itself went wrong |

With `--only=<id>` the run is about one mutant, so it passes when that mutant is caught, as `verify` does, and says the mutant's fate rather than a score.

The commands mutastic prints for a mutant name the project it ran on (`mutastic verify m855c8e3c samples/pricing`), so they work as copied. A mutant's id belongs to its project: run in a directory with no Java code, or one that isn't a Gradle or Maven build, mutastic says so (for Gradle, it names the modules that have Java code). A Maven module is named by its directory, as `-pl` takes it: `mutastic run . :services/api`.

The result goes to stdout and progress to stderr, so `mutastic run > result.txt` keeps only the result.

- `--format=full` (the default on a terminal) shows each missed mutant as a card: the gap in the tests, the code with the change marked, the evidence (what the tests passed in, and which tests run it), the test to add, and how to prove it. Cards are grouped by file, and in each file the method with the most misses comes first. On a terminal, file locations are links (`--hyperlinks`) that terminals supporting OSC 8 open; others show the text.
- `--format=brief` keeps the gap, the changed line and the evidence; `--format=concise` (the default elsewhere, such as CI logs) is one line per missed mutant; `--format=grouped` is one line each under its file and method.
- `--format=agent` (the default when `MUTASTIC_AGENT=1`) is plain text for coding agents: the verdict first, so a truncated read still has it, then each missed mutant with the change, the fix and how to prove it, and the rule that a fix changes tests, never production code.
- `MUTASTIC_FORMAT` sets the format when `--format` isn't given. While it runs, each source file gets a line as it finishes, on stderr, with its misses under it.
- `--vocabulary=classic` (or `MUTASTIC_VOCABULARY=classic`) says killed and survived instead of caught and missed. JSON and the report files always use the standard statuses.
- Colour follows the usual rules: only on a terminal, never with `NO_COLOR` or `TERM=dumb`, always with `FORCE_COLOR`. Every colour has a symbol beside it (✓ caught, ✗ missed, ○ not covered, ⏱ timed out), so nothing is lost without it. Labels such as `MISSED` and `PASS` are coloured blocks, `[MISSED]` without colour, and have rounded ends with `MUTASTIC_PILLS=rounded` for terminals with a Nerd Font. `MUTASTIC_PILLS=off` draws no blocks: the label's words in its colour.
- `--format=jsonl` is one JSON event per line, written as each mutant finishes (`mutastic.events/v1`). Statuses follow the mutation-testing report schema: `Killed`, `Survived`, `Timeout`, `NoCoverage`.
- A mutant's id comes from its class, method, position and change, not its line number, so it stays the same while code around it moves.
- Some mutants are skipped, with the reason given: a change that can't be told apart from the original (`return 0` becoming "return the default"), code the compiler wrote (a `switch` on a `String`, a record's `equals()`), calls that only write output (logging, `System.out`), and trivial accessors: a getter that only returns one of its class's fields, or a setter that only stores its argument in one, named for that field. An accessor with any logic (a copy, a check, lazy set-up, a notification) is mutated like any code, and `@DoMutate` (any annotation of that name) on a method or class brings its trivial accessors back. Every `toString()` is a debug representation and every `hashCode()` may return any value consistent with `equals()`, so both are skipped too, with the lambdas they define and the helpers only they call; `@DoMutate` keeps one that's a contract. `@DoNotMutate` or `@CoverageIgnore` skips a method or class, with its `value()` or `reason()` as the reason, and `@Generated` marks code a tool wrote. Annotations are matched by simple name, so your own work; or depend on `io.mutastic:mutastic-annotations`, a small jar with `@DoNotMutate` and `@DoMutate` and nothing else, read from the class files and never needed at run time. Skipped mutants don't count towards the score.

A project's own say goes in `mutastic.yaml`, in its directory:

```yaml
rules:
  - skip: com.acme.generated.**      # ** any depth, * one name, Class#method one method
    reason: protobuf output
  - mutate: com.acme.Money#toString  # brings back what mutastic would skip as arid
    reason: shown to customers
plugins:
  - build/libs/acme-filters.jar      # jars with filters of your own (io.mutastic.api.Filter)
filters:
  - acme:no-metrics                  # a plugin's filter runs only when named here
```

An annotation on the code comes first, then a rule, then mutastic's own filters. A `mutate:` rule, like `@DoMutate`, brings back only what mutastic would call arid, never generated or equivalent code. Every rule needs a reason, which is shown with each mutant it skips.

`mutastic explain ID [dir] [:module]` runs that one mutant and shows its card, whatever became of it, then what it is: the change in the bytecode, opcode by opcode (`IF_ICMPLT → IF_ICMPLE`), and how the jump relates to the source, since javac compiles `if (total >= 100)` to a jump taken when `total < 100`; what its operator does; how its id is made and why it survives edits; which site it is; and how the last full run went. The summary's `Next:` line offers it, for the one-line formats.

For one mutant no test should catch, `mutastic ignore ID [dir] [:module] --reason="…"` records the decision in `.mutastic/ignores.yaml`, with where the mutant is, what it changes, the line it's on and the reason. Commit it: each entry is a decision someone reviews. An ignore holds until its line changes; then the mutant runs again, and the run says the ignore no longer holds (`mutastic ignore --prune` removes those). Each says where it wrote and how to undo it: `mutastic ignore --remove ID` takes one back. Every missed mutant's card offers the command (`or ignore`), and an ignore never hides code the compiler wrote or a change that can't be told apart from the original.

To see what the arid skips hide, `MUTASTIC_ARID=audit` runs them anyway and adds an `Arid audit` row: how many ran, how many were caught and how many of those by a test's own assertion (a check on what the code returns, which may make it a contract worth `@DoMutate`), and where.

### Report files

`--since=main` mutates only the lines changed since the branch left `main` (its merge-base), uncommitted edits and new files included: a pull request's or an agent's change, which is where a missed mutant is worth acting on. A bare `--since` means the repository's default branch. The summary says what the run covered (`Changed  12 lines in 3 files since main`); a scoped run writes its reports but doesn't replace the last full run's record.

A module's own tests are often not all that test it: another module that uses it may test it more. `--tests-from=:app` (as often as needed) runs `:app`'s tests over the mutants the module's own tests leave uncaught, as `:app`'s build runs them, with this module's mutated classes in place of its jar; a mutant caught by either is caught. `mutastic run :core --tests-from=:app`, or `--tests-from=dependents` for every module that depends on this one, directly or through others, as the build's own graph has it.

A full run also writes report files to `build/mutastic/` (for a Gradle module, `build/mutastic/<module>/`; for Maven, the module's own `target/mutastic/`), by default `mutation.json` and `mutation.html`. `--report` chooses (it can be given more than once, and replaces the defaults): `--report=xml` adds `mutations.xml`, `--report=json:reports/m.json` writes somewhere else, and `--report=none` writes nothing.

- `json` follows the open mutation-testing report schema (version 2), which report viewers, dashboards and badges read.
- `html` is that report in the schema's own viewer, embedded in the page, so it opens from disk with no network.
- `xml` is the `mutations.xml` format that many build-server and code-quality plugins read.

### Inputs seen and suggested tests

Each missed mutant shows the whole numbers its covering tests passed to its method (`inputs seen: total = 50, 250`), so what they never tried is plain.

For a missed mutant in a method whose parameters are all whole numbers (`int`, `long`, `Integer`, `Long`), mutastic also looks for a test that would catch it. The method can be static, or on an object mutastic can make: with a no-argument constructor, or one whose parameters are whole numbers too, as a record of numbers has. The call must be one the test class could write itself (nothing private, nothing package-private from another package). It calls the method with candidate inputs, first as it is and then with the mutant in, and keeps the first input where the results differ. The constants near the change, ±1, come first, then what the tests passed, then a few small numbers. The test is written for the class of the fastest test that already reaches the mutant, in its assertion style (AssertJ or JUnit), with a name it doesn't already use:

```java
// in PricingTest
@Test
void discountWithTotal100() {
  assertEquals(10, new Pricing().discount(100)); // today 10 · with this mutant 0
}
```

A method that answers differently to the same call (it uses a random number or the clock) gets no suggestion. The expected value is what the code does today. Check it's what you intend before keeping the test. The search runs your code outside your tests, in the test worker with a time limit; `--no-suggestions` turns it off.

### Flaky tests

A flaky test fails at random, so against mutants it "catches" changes it never checked, and the score goes up and down with no change to the code. mutastic looks for them in three ways:

- **Before any mutant.** A test that fails in the first run is run again on its own, up to three times. If it passes, mutastic leaves it out of the run and names it (`flaky: …`). A test that fails every time stops the run: the tests are red.
- **Between runs.** Each full run keeps its results in `last-run.tsv` beside its reports, one per module. If the code and tests are unchanged and a mutant's result changed, the test that caught it is named (`likely flaky: …`).
- **With `--confirm-catches`.** Each catching test runs once more against its mutant. A catch that doesn't repeat doesn't count; the other covering tests decide. This costs one more test run per catch.

A test that catches mutants in several unrelated methods, each time with the same exception thrown in its own code, also gets a `note:`. It may be flaky, or it may just call a lot of code.

### Operators

| Operator | Change |
|---|---|
| `mutastic/boundary` | `<` ↔ `<=`, `>` ↔ `>=` |
| `mutastic/negate-conditional` | a condition goes the other way: `==` ↔ `!=`, `<` ↔ `>=`, `== null` ↔ `!= null` |
| `mutastic/math` | `+` ↔ `-`, `*` ↔ `/` on `int` and `long` |
| `mutastic/return-default` | return `0`, `false` or `null` instead |
| `mutastic/remove-void-call` | a call that returns nothing is removed |

## How it works

1. **Setup.** mutastic runs your build with its own code loaded, which compiles the code and tests and records what the tests would run with: their JDK, JVM arguments, working directory and classpath. For Gradle that's an init script reading the `test` task; for Maven, an extension (`-Dmaven.ext.class.path`) reading Surefire's settings after `mvn test-compile`. Your build files are not changed.
2. **Mutants.** The engine reads your class files with the JDK's ClassFile API (`java.lang.classfile`, final in JDK 24) and finds every mutation site.
3. **Coverage.** A test worker starts on your project's JDK and runs every test once, with each line instrumented. This records which tests reach which lines, and doubles as a check that the tests pass.
4. **Mutants, side by side.** For each mutant, the engine sends the changed class to a worker. The worker runs only the tests that reach that line, fastest first, and stops at the first failure. Classes load once per worker and each mutant is swapped in with class redefinition, so nothing is restarted between mutants. A worker that takes too long is killed and replaced: a mutant can turn a loop into one that never ends.

   Several workers run at once (`--workers`, by default half the cores, at most 4), each its own JVM, as Gradle's parallel test forks are. Results are reported in source order whatever order they finish in. Workers side by side can get in each other's way: tests that use a fixed port collide, and tests that share a lock, a database or a slow start (a Spring context) run slower than they did alone. So neither a catch caused by a port in use nor a timeout is trusted: that mutant runs again on its own once the others are done.

Code is in nine modules:

| Module | JDK | What it does |
|---|---|---|
| `mutastic-annotations` | 17 | `@DoNotMutate` and `@DoMutate`, for your code: nothing else of mutastic's |
| `mutastic-api` | 25 | The result model every other module shares: a project, its mutants and their results |
| `mutastic-engine` | 25 | Finds, applies and verifies mutants; coverage; runs the worker |
| `mutastic-worker` | 17 | Runs your tests on your JDK; a Java agent for swapping classes |
| `mutastic-report` | 25 | The formats: for people, for agents, JSON Lines, and the report files |
| `mutastic-gradle` | 25 | Reads a Gradle build's setup, with an init script; a `BuildTool` |
| `mutastic-maven` | 25 | Reads a Maven build's setup, with the extension; a `BuildTool` |
| `mutastic-maven-extension` | 17 | Runs inside your Maven build and writes what Surefire would run |
| `mutastic-cli` | 25 | The `mutastic` command: finds the build tool, runs the engine, picks the format |

## Building

```sh
./gradlew build
```

Gradle downloads the JDKs it needs (25 and 17). The end-to-end tests run mutastic against `samples/pricing`, a JDK 17 project.

## Licence

Apache License 2.0. See [LICENSE](LICENSE).
