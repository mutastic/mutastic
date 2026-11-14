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
- **The HTML report:** the page both editions embed is `report-ui/`: its markup, styles and
  script in `src/`, and the fonts. After changing them, run `node report-ui/build.mjs` and commit
  `report.html` and its checksum with them; `node --test report-ui/test/*.test.mjs` renders it in
  Chrome. `node report-ui/build.mjs --embed mutation.json out.html` puts a run's report in it, to
  look at.
- **Dependencies:** pin exact versions, then `./gradlew lockDependencies --write-locks`.

## Keeping the editions level

mutastic has a Java edition (this repository) and a Rust edition
([mutastic-rs](https://github.com/mutastic/mutastic-rs)), and they should look the same to a
person, an agent and a CI job. Without a check, a difference is found only by running both and
noticing. The editions' contract makes each one a test failure instead.

**The contract** is [`docs/public/contract/v1.json`](docs/public/contract/v1.json)
(`mutastic.contract/v1`), described by the JSON Schema
[`docs/public/schemas/contract/v1.json`](docs/public/schemas/contract/v1.json) and published with
it on docs.mutastic.io. It holds what the editions must agree on:

- the command line: commands, synopses, the options each takes and in what order, kinds, value
  names, choices, defaults, environment variables and exit codes;
- every error code with its exit code, the pairs that mean the same thing (`errorPairs`), and a
  table of error cases (`errorCases`) with the code and exit code each must end with;
- help's layout, the human summary's rows and their order, and the agent format's line shapes
  (its head, each entry, and the tail that closes the fence around the project's text);
- the events' key paths per event type and their order, and mutation.json's key paths;
- the events, report and SARIF schemas, by sha256;
- the 15 decisions taken on 13 Nov 2026 (`decisions`), each a shared rule;
- `differences`: every known way an edition differs, each one of three kinds:
  - `engine`: permanent, with a `reason` another edition would share (Gradle modules, `unsafe`);
  - `dated`: with an `until` (`rust 2.0.0`);
  - `pending`: a gap, a drift or a decision not yet carried out, with an `issue`.

**The checks.** Each edition runs its own binary on its own pricing fixture, as a person does, and
compares what it does with the contract: its `commands.json`, `--help` and each command's help,
the error cases, a full run's summary, `--format=agent` and `--format=jsonl`, and mutation.json.
Here that's `ContractTest` (in `mutastic-cli`); in mutastic-rs it's `tests/contract.rs`. Each
difference is found as an item, such as `takes:baseline:--operators` or `error:gate:code`. An item
no difference for that edition names fails, saying what to do. A difference whose `items` name
nothing that was found fails too, as it now matches: take the edition out of its `editions`, or
the entry out. So the list only shrinks when the editions really meet. A difference without
`items` describes something no check reaches yet, and stays until someone removes it.

**Adding an option to both editions:**

1. Add it to the contract: to `options`, and to each command's `options` in the order help lists
   them. If one edition will have it first, add a `pending` difference for the other, with items
   `option:--name` and `takes:*:--name` and an issue.
2. Add it here, run `./gradlew :mutastic-cli:exportDocsModel`, and `ContractTest` passes.
3. mutastic-rs pins the contract by commit (`contract.json`). When the Rust side is written, its
   contract check fails with "now matches". Take `rust` out of the pending entry in a mutastic PR,
   then move the pin in the Rust PR to that merge commit. Its weekly drift job, against mutastic
   main, says when the two are out of step.

An option only one edition will ever have, for a reason of its engine, is an `engine` difference
with its reason. A change to shared output (a summary row, an agent line) goes the same way: the
contract first, then each edition.

**A new edition** (Swift, say) adopts the contract on its first day:

1. Add it to `editions`.
2. Port the pricing fixture, and write its checks from mutastic-rs's `tests/contract.rs`: they
   read only the contract, the edition's `commands.json` and what its binary prints.
3. Every failure becomes a `pending` difference with an issue, or an `engine` one with a reason,
   reviewed in one pull request. Its engine slots are already named: the summary's unit row, the
   `Build` row, the toolchain in `Tests`, and `mutation.applied.level`.

**The docs' captures, compared.** The docs build pairs each edition's captured output by scenario
([`captures.json`](docs/public/contract/captures.json) maps Java's flat names to Rust's
folders), masks ids, paths, timings, names and code, and warns where the shapes differ
(`docs/scripts/compare-captures.mjs`; `node docs/scripts/compare-captures.mjs <mutastic-rs docs>`
lists them). Warnings for now, while the masks settle.

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

A pull request is squash-merged, and GitHub writes its title and " (#<number>)" as the subject on
`main`, where the check counts the whole line. So the title follows the same rules with the
suffix counted: at most 44 characters for #10 to #99, 43 for #100 to #999. CI checks it
(`.githooks/check-title`), again whenever the title is edited, and says the limit for the pull
request's number.

Merge with `.githooks/merge-pr`, not `gh pr merge` by hand. It writes the subject from the title,
takes the body from a file (wrapped at 72) or leaves it empty, checks the whole message with
`commit-msg`, and merges only once every check has passed, on the commit they ran on. It deletes
the branch on GitHub and leaves local branches and worktrees alone. `--admin` merges without
waiting for the checks, for one that can't pass; the message is still checked.

```sh
sh .githooks/merge-pr 59            # subject "<title> (#59)", no body
sh .githooks/merge-pr 59 body.txt   # with a body that says why
```

## Licence

mutastic is under the [Apache License 2.0](LICENSE). Contributions are accepted under the same
licence: by opening a pull request, you agree yours is offered under it.
