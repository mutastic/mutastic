# mutastic's own score

mutastic runs on its own engine every night (`.github/workflows/nightly.yml`). This page records
the score at each milestone and what moved it. The nightly keeps every run's numbers in
`score-history.tsv`, attached to each run.

The score is a measure of mutastic as much as of its tests. Prior work found that most of a mutation tool's
cost to its users is noise, not missing tests:

- **Google** found developers "are not willing to waste their time on writing pointless tests just to
  satisfy mutation adequacy". Suppressing arid lines and scoping mutants to the diff raised
  productive mutants from 15% to 89% ([Petrović et al., TSE 2021](https://arxiv.org/pdf/2102.11378)).
- **cargo-mutants'** [stated goals](https://github.com/sourcefrog/cargo-mutants/blob/main/book/src/goals.md)
  include "reproducible", "avoid trivial mutants", an easy permanent skip, and "most results should
  point to code that deserves more testing".
- **Users of these tools** named performance as the main limitation in a 104-developer survey
  ([IEEE TSE 2024](https://ieeexplore.ieee.org/document/10472898/)). A Stryker user working with
  coding agents asked for an inline way to dismiss a mutant
  ([Test Double](https://testdouble.com/insights/keep-your-coding-agent-on-task-with-mutation-testing)).
  In the wild, the median share of equivalent mutants is only about 3%
  ([Kushigian et al., ISSTA 2024](https://2024.issta.org/details/issta-2024-papers/53/Equivalent-Mutants-in-the-Wild-Identifying-and-Efficiently-Suppressing-Equivalent-Mu)).

So a miss on this page is either a test mutastic's own tests lack, or noise mutastic should have
filtered. Each milestone says which.

## Reproduce

```sh
./gradlew :mutastic-cli:installDist
./mutastic-cli/build/install/mutastic/bin/mutastic run :mutastic-engine
```

## Milestones

| Date | Commit | Mutants | Caught | Missed | Timed out | Not covered | Skipped | Score | Time |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-11-01 | `38db632` | 1,540 | 767 | 167 | 3 | 535 | 68 | 52.3% | 30 s |

### 2026-11-01: the first self-run

The baseline, taken when the build's conventions went in. It covers the engine module (packages
`engine` and `report`), using its own 63 tests on JDK 25.

- **Not covered (535) is mostly a gap in mutastic, not in the tests.** The CLI module's end-to-end
  tests run nearly all of this code, but a module's run counts only that module's tests. Combined, JaCoCo puts the
  engine at 82% and the report package at 72% of branches. Counting tests from other modules is the same finding as
  the cross-module spike: a real multi-module score needs it.
- **Skipped (68):** 53 generated (javac's own code, such as try-with-resources), 9 equivalent and
  6 arid. These filters are already doing their job. With no filters, the score would read lower and the misses
  would include code nobody should test.
- **Missed (167)** cluster in `Operands.call()` (13), `ReportFiles.mutation()` (7) and
  `HumanText.warnings()` (5). The first is the code that reads operands from bytecode. Its misses
  are real gaps: unit tests cover only the simple shapes.
- **Not fully reproducible:** a second run on the same code scored 52.4%. One mutant close to its
  timeout gave a different result, and 2 workers were replaced after timeouts. That breaks
  cargo-mutants' "reproducible" goal, and is worth a look before the score is used to gate anything finer than the
  50% floor.
- **Coverage selection** ran 2,651 tests where running every test against every mutant would take
  59,031.

## How to read a change

- **Score up, misses down, skips unchanged:** better tests.
- **Skips up:** a new filter. Check the skipped mutants are noise, with `MUTASTIC_ARID=audit` for
  the arid filter. A filter that hides real misses makes the score lie, and this page should say so.
- **Not covered down without new tests:** better attribution of tests to mutants, such as counting tests
  from other modules.
- **Same commit, different score:** flakiness, in mutastic or in its tests.
