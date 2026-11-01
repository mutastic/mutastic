# mutastic

*Find out whether your tests, or your agent's, actually check anything.*

mutastic is a mutation-testing tool for Java. It makes small changes to your compiled code, such as `>=` to `>`, and runs your tests against each one. A change that no test notices is a **missed mutant**: a place where your tests run the code but don't check what it does.

> **Status: early.** This is the first end-to-end slice. It has one operator (boundaries), Gradle projects with JUnit Platform tests, and agent and JSONL output. Expect everything to change.

## Try it

You need JDK 25 to build and run mutastic. Your project can be on JDK 17 or later: its tests run on its own JDK.

```sh
./gradlew :mutastic-cli:installDist
./mutastic-cli/build/install/mutastic/bin/mutastic run samples/pricing
```

```
mutastic: FAIL · score 50.0% (threshold 80) · 1 missed, 0 not covered, 1 caught of 2 mutants
rules: add or change tests only, never production code; a fix counts when `mutastic verify <id>` passes

1. missed m855c8e3c: no test checks the edge of `>=` in `discount()`
   at src/main/java/com/example/pricing/Pricing.java:17
   - if (total >= 100) {
   + if (total > 100) {
   covered by: PricingTest#bigOrdersGetTenPercentOff, PricingTest#smallOrdersPayFullPrice; all still pass with the change
   fix: add a test with the values where `>=` and `>` disagree (both sides equal), and assert the result
   reproduce: mutastic run --only=m855c8e3c
   done when: mutastic verify m855c8e3c passes
```

Add a test for an order of exactly 100, then:

```sh
mutastic verify m855c8e3c samples/pricing
# mutastic: m855c8e3c is caught by com.example.pricing.PricingTest#anOrderOfExactlyOneHundredGetsTheDiscount
```

## Usage

```
mutastic run [dir] [:module] [--format=agent|jsonl] [--threshold=N] [--only=ID]
mutastic verify ID [dir] [:module]
```

| Exit code | Meaning |
|---|---|
| 0 | The score reaches the threshold (80 by default); for `verify`, the mutant is caught |
| 1 | It doesn't |
| 2 | mutastic couldn't run: a usage error, a failing build, or tests that fail without any mutant |

- `--format=agent` (the default) is plain text for coding agents and people: the verdict first, then each missed mutant with the change, the tests that run it, the fix and how to prove it.
- `--format=jsonl` is one JSON event per line, written as each mutant finishes (`mutastic.events/v1`). Statuses follow the mutation-testing report schema: `Killed`, `Survived`, `Timeout`, `NoCoverage`.
- A mutant's id comes from its class, method, position and change, not its line number, so it stays the same while code around it moves.

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
