# Working on mutastic

mutastic is a mutation-testing tool for Java. Read the [README](README.md) for what it does and
[ARCHITECTURE.md](ARCHITECTURE.md) for how it's built. This file is the conventions the code is held to, and why. Most are enforced by
the build; the rest are judgement, written down so they're designed around rather than met as a
build failure.

## Build

```sh
./gradlew build          # compile, Error Prone and NullAway, format, Checkstyle, tests, ArchUnit,
                         # and the branch-coverage floor (65%, target 85%)
./gradlew spotlessApply  # format the code
./gradlew coverageReport # build/reports/jacoco/coverageReport/html, over every module's tests
./gradlew lockDependencies --write-locks  # after changing a version: rewrites gradle.lockfile
./gradlew :mutastic-cli:exportDocsModel    # after changing an option or command: the docs' reference
./gradlew :mutastic-cli:exportDocsOutput   # after changing what a command prints: the output the docs show
./gradlew :mutastic-cli:exportCards        # after changing a card's words: every card on the samples; read the diff
./gradlew :mutastic-cli:installDist && ./mutastic-cli/build/install/mutastic/bin/mutastic run samples/pricing
```

The engine runs on JDK 25 (the ClassFile API); the worker is built for 17, the oldest JDK a
project under test can use. Gradle downloads both.

## Code conventions

Formatting follows the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html),
applied by google-java-format through Spotless: that is the spec, not taste.

1. Every warning fails the build. A warning nobody acts on hides the next one.
2. One formatter, pinned to an exact version. Layout is not a conversation.
3. A snippet must stand alone. Code is read without its imports or its file — in a review, a
   chat, an article. Prefer the form that survives being torn out.
4. Limits are set where the code already is, then held. A limit above the code is one you have
   already failed.
5. When something exceeds a limit, split it and name the pieces. Do not raise the bar, and
   never lower a floor (coverage, score) to make a build pass: fix what moved it.
6. A check earns its place by finding something worth fixing. Turn it on, run it over
   everything, delete it if it found nothing.
7. When you reject a rule, write the reason beside the rule — not in a suppressions file.
8. Name and structure carefully; let the formatter handle whitespace. Identifiers and shape
   change what a reader and a model understand. Indentation changes neither.
9. Boundaries are asserted in tests, not described in comments.
10. A test that skips when its inputs are missing has checked nothing. Let the everyday task
    skip; make CI run one that fails.
11. A line should read as a sentence. Name locals for what they hold. Use `var` when the
    right-hand side names the type, and keep the type when the declaration is the only place
    it's said.
12. Name what you'd otherwise repeat or imply. A call made three times becomes a local. A stream
    of three or more stages inside a bigger method becomes a private method named for what it
    returns.
13. Say what the collection is. `contains` followed by `add` means a `Set`: use `LinkedHashSet`
    for order, and declare `SequencedSet` when callers only iterate.
14. Name every regex group, including the ones nothing reads back. Never read a group by number.
15. One concern per type. Caching, prefixing or retrying wraps the thing it adds to (`X` and
    `CachingX`) instead of being folded into it.

## mutastic's own rules

- **Output never names other mutation tools.** Cards, `explain`, help text and rule files
  describe operators in mutastic's own terms. Comparisons belong in code comments and notes. The
  one exception is data a format's consumers key on, such as the mutator names in
  `mutations.xml`, with a comment saying why.
- **A command the output offers exists.** Per-mutant commands come from `MutantCommand`, and
  `MutantCommandTest` checks each one parses; add a command to both sides or neither.
- **Ids never change.** A mutant's fingerprint is its class, method, site and replacement. A new
  operator, filter or output must not move an existing id.
- **A false catch is worse than a miss.** Anything that can make a mutant look caught when the
  tests didn't catch it (a flaky test, a port clash, a mutant that doesn't load) is treated as
  not caught, and said so.
- **A card says what its mutant does.** People and agents both act on a card's words, so a wrong
  one costs an agent's tokens, or a wrong ignore, as well as trust. `CardTruthTest` writes each
  condition card's claim into the source and checks the mutant behaves as that source does, for
  every shape javac compiles a condition to: a new way of saying a condition, or a new shape the
  engine reads, goes in its fixture. `CardsTest` keeps every card on the samples, so a change to
  their words is a reviewed diff.
- **Check claims against real projects,** not only the samples: the bugs that mattered were found
  there.
