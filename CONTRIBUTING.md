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
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org) and 50/72: a
  `type(scope): subject` of at most 50 characters, imperative and lower case, a blank line, then a
  body wrapped at 72 that explains why: what was wrong or missing, and what the change does about it.
- CI must pass. A pull request that changes the docs gets a preview link of its own.

## Commit messages

What [Pull requests](#pull-requests) asks of a commit message, in full:

- **Subject:** no full stop at the end. Imperative means "add", not "adds" or "added".
- **Type:** `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `build`, `ci`, `chore` or
  `revert`. A breaking change adds `!` before the colon (`feat(cli)!: …`), or a
  `BREAKING CHANGE:` footer.
- **Scope:** optional and lower case, the module or area changed: `engine`, `worker`, `cli`,
  `report`, `api` (the API and the annotations), `plugins`, `gradle` (and the Gradle plugin),
  `maven` (the extension and the plugin), `samples`, `docs`, `ci` or `release`. Leave it out when a
  change spans several.
- **Long lines:** URLs, indented or fenced code, and trailers such as `Co-Authored-By:` (last)
  may run past 72.

Two from the log, as they'd be written now:

```text
fix(maven): run mvn.cmd on Windows

Before a Windows build is promised, the end-to-end tests have to pass
there. On windows-latest Maven never started, as mutastic ran mvnw, not
mvnw.cmd; paths came out with \ and lines mixed LF and CRLF. Cards and
ids are now the same as on Linux, and a Windows job keeps them so.
```

```text
fix(api): list covering tests in a stable order

Cards listed covering tests fastest first, and timings move with load,
so a slow build swapped two tests on a card and a docs test failed. A
card is compared across runs and read by agents, so its order must not
move: the mutant's own test class comes first, then the rest by name.
```

Commits before 9 Nov 2026 use the older style, a long subject that says why, and are not
rewritten.

`.githooks/commit-msg` checks each message as you commit, and lets merge, revert, `fixup!` and
`squash!` commits through as git writes them. Turn it on once in each clone:

```sh
git config core.hooksPath .githooks
```

CI runs the same check on each new commit in a push or pull request (`.githooks/check-commits`),
counting from the commit that added the hook. `sh .githooks/test-commit-msg` tests the hook.

## Licence

mutastic is under the [Apache License 2.0](LICENSE). Contributions are accepted under the same
licence: by opening a pull request, you agree yours is offered under it.
