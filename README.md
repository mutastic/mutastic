# mutastic

*Find out whether your tests, or your agent's, actually check anything.*

mutastic is a mutation-testing tool for Java. It makes small changes to your compiled code, such as `>=` to `>`, and runs your tests against each one. A change that no test notices is a **missed mutant**: a place where your tests run the code but don't check what it does.

> **Status: early.** Five operators (boundaries, negated conditions, arithmetic, returned defaults, removed void calls), Gradle projects with JUnit Platform tests, and output for people, agents and tools. Expect everything to change.

## Try it

You need JDK 25 to build and run mutastic. Your project can be on JDK 17 or later: its tests run on its own JDK.

```sh
./gradlew :mutastic-cli:installDist
./mutastic-cli/build/install/mutastic/bin/mutastic run samples/pricing
```

```
✗ missed[boundary] m855c8e3c no test checks the edge of `>=` in `discount()`
  --> src/main/java/com/example/pricing/Pricing.java:17
   |
16 |   public int discount(int total) {
17 |     if (total >= 100) {
   |               ^^ `>=` becomes `>` and all 2 covering tests still pass
18 |       return total / 10;
   |
   = mutant  - if (total >= 100) {
             + if (total > 100) {
   = note: covered by PricingTest#bigOrdersGetTenPercentOff, PricingTest#smallOrdersPayFullPrice
   = help: add a test with the values where `>=` and `>` disagree (both sides equal), and assert the result
   = reproduce: mutastic run --only=m855c8e3c
   = done when: mutastic verify m855c8e3c passes

  Mutants  ✓ 6 caught · ✗ 1 missed · ○ 0 not covered · ⏱ 0 timed out (7) · 2 skipped
 Worst at  Pricing.discount() 1 ✗
 Duration  914ms (setup 505ms, coverage 292ms, mutants 115ms)
    Tests  3 on JDK 17
    Score  85.7%  threshold 80 ✓

Next: mutastic run --only=<id> · mutastic verify <id>
```

Add a test for an order of exactly 100, then:

```sh
mutastic verify m855c8e3c samples/pricing
# mutastic: m855c8e3c is caught by com.example.pricing.PricingTest#anOrderOfExactlyOneHundredGetsTheDiscount
```

## Usage

```
mutastic run [dir] [:module] [--format=full|concise|agent|jsonl] [--threshold=N] [--only=ID]
             [--confirm-catches] [--color=auto|always|never] [--progress=auto|bar|counter|none]
mutastic verify ID [dir] [:module]
```

| Exit code | Meaning |
|---|---|
| 0 | The score reaches the threshold (80 by default); for `verify`, the mutant is caught |
| 1 | It doesn't |
| 2 | A usage error, or a build mutastic couldn't read |
| 3 | The tests fail without any mutant (a flaky test that passes when run again doesn't count) |
| 4 | mutastic itself went wrong |

The result goes to stdout and progress to stderr, so `mutastic run > result.txt` keeps only the result.

- `--format=full` (the default on a terminal) shows each missed mutant as a card: the code with the change marked, the tests that run it, the test to write, and how to prove it. The summary comes last and ends on the score, with the change since the last run.
- `--format=concise` (the default elsewhere, such as CI logs) is one line per missed mutant, then the summary.
- `--format=agent` (the default when `MUTASTIC_AGENT=1`) is plain text for coding agents: the verdict first, so a truncated read still has it, then each missed mutant with the change, the fix and how to prove it.
- Colour follows the usual rules: only on a terminal, never with `NO_COLOR` or `TERM=dumb`, always with `FORCE_COLOR`. Every colour has a symbol beside it (✓ caught, ✗ missed, ○ not covered, ⏱ timed out), so nothing is lost without it.
- `--format=jsonl` is one JSON event per line, written as each mutant finishes (`mutastic.events/v1`). Statuses follow the mutation-testing report schema: `Killed`, `Survived`, `Timeout`, `NoCoverage`.
- A mutant's id comes from its class, method, position and change, not its line number, so it stays the same while code around it moves.
- Some mutants are skipped, with the reason given: a change that can't be told apart from the original (`return 0` becoming "return the default"), code the compiler wrote (a `switch` on a `String`, a record's `equals()`), and calls that only write output (logging, `System.out`). Skipped mutants don't count towards the score.

### Flaky tests

A flaky test fails at random, so against mutants it "catches" changes it never checked, and the score goes up and down with no change to the code. mutastic looks for them in three ways:

- **Before any mutant.** A test that fails in the first run is run again on its own. If it passes, mutastic leaves it out of the run and names it (`flaky: …`). A test that fails twice stops the run: the tests are red.
- **Between runs.** Each full run keeps its results in `build/mutastic/last-run.tsv`. If the code and tests are unchanged and a mutant's result changed, the test that caught it is named (`likely flaky: …`).
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

1. **Setup.** mutastic runs your Gradle build with an init script, which compiles the code and tests and records what the `test` task would use: its JDK, JVM arguments, working directory and classpath. Your build files are not changed.
2. **Mutants.** The engine reads your class files with the JDK's ClassFile API (`java.lang.classfile`, final in JDK 24) and finds every mutation site.
3. **Coverage.** A test worker starts on your project's JDK and runs every test once, with each line instrumented. This records which tests reach which lines, and doubles as a check that the tests pass.
4. **Mutants, one at a time.** For each mutant, the engine sends the changed class to the worker. The worker runs only the tests that reach that line, fastest first, and stops at the first failure. Classes load once per worker and each mutant is swapped in with class redefinition, so nothing is restarted between mutants. A worker that takes too long is killed and replaced: a mutant can turn a loop into one that never ends.

Code is in three modules:

| Module | JDK | What it does |
|---|---|---|
| `mutastic-engine` | 25 | Finds, applies and verifies mutants; coverage; runs the worker; reports |
| `mutastic-worker` | 17 | Runs your tests on your JDK; a Java agent for swapping classes |
| `mutastic-cli` | 25 | The `mutastic` command and the Gradle integration |

## Building

```sh
./gradlew build
```

Gradle downloads the JDKs it needs (25 and 17). The end-to-end tests run mutastic against `samples/pricing`, a JDK 17 project.

## Licence

Apache License 2.0. See [LICENSE](LICENSE).
