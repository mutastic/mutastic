# Contributing to mutastic

Thanks for helping. Bug reports, ideas and pull requests are all welcome.

## Reporting a bug or asking for something

Open an [issue](https://github.com/mutastic/mutastic/issues/new/choose). For a bug, the form asks
for what helps most: `mutastic --version`, how you ran it (the command line, Maven or Gradle), your
JDK, the command and what it printed. A small project that shows the problem is best of all.

For a security problem, don't open an issue: see [SECURITY.md](SECURITY.md).

Everyone taking part in issues, pull requests and discussions agrees to the
[Code of Conduct](CODE_OF_CONDUCT.md). To report a breach of it, write to conduct@mutastic.io.

## Setting up

You need a JDK to run Gradle. Gradle fetches the JDKs the build uses (25 for mutastic, 17 for the
worker and the code it tests). The Maven plugin's and Maven samples' tests also need `mvn` on the
`PATH`, and the docs need Node 24.

```sh
git clone https://github.com/mutastic/mutastic.git
cd mutastic
./gradlew build
```

`./gradlew build` runs everything CI does: compilation with every warning an error, Error Prone
and NullAway, the formatter, Checkstyle, the tests (end-to-end ones included, which run real Gradle
and Maven builds), the architecture rules and the coverage floor. It takes a few minutes.

Useful along the way:

```sh
./gradlew spotlessApply                                   # format the code
./gradlew :mutastic-cli:test --tests '*CommandTest'       # one module's tests, or one class's
./gradlew :mutastic-cli:installDist                       # the mutastic command, built from here
./mutastic-cli/build/install/mutastic/bin/mutastic run samples/pricing
```

[ARCHITECTURE.md](ARCHITECTURE.md) explains how the pieces fit, and `samples/` holds the small
projects the end-to-end tests run on.

## Making a change

- **Follow [AGENTS.md](AGENTS.md).** It's the conventions the code is held to and why: one
  formatter, limits split rather than raised, names that read as words, boundaries asserted in
  tests. Most are enforced by the build; the rest are judgement, written down.
- **mutastic's own rules:**
  - Its output never names other mutation-testing tools.
  - Every command it offers exists: `MutantCommandTest` checks each parses.
  - A mutant's id never changes: it's the mutant's class, method, site and change.
  - A catch it can't trust (a flaky test, a port clash) counts as not caught.
- **Tests:** a change in behaviour comes with a test that fails without it.
- **Docs:** user-facing changes update the docs in `docs/` (see [docs/README.md](docs/README.md)).
  After changing an option or a command, run `./gradlew :mutastic-cli:exportDocsModel`; the build
  fails until the docs' reference matches. After changing what a command prints, run
  `./gradlew :mutastic-cli:exportDocsOutput`, for the output the docs show.
- **Dependencies:** pin exact versions, then `./gradlew lockDependencies --write-locks`.

## Pull requests

- One change per pull request, with a description of what it changes and why. The template asks.
- Commit messages explain why, as the log does: what was wrong or missing, and what the change does
  about it.
- CI must pass. A pull request that changes the docs gets a preview link of its own.

## Licence

mutastic is under the [Apache License 2.0](LICENSE). Contributions are accepted under the same
licence: by opening a pull request, you agree yours is offered under it.
