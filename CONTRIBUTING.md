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
`PATH`, the docs need Node 24, and the build's commands need [just](https://just.systems) 1.59.0
or later: `brew install just`, `scoop install just`, `winget install --id Casey.Just` or
`cargo install just --locked`. On Windows, recipes run in Git Bash's `sh`.

```sh
git clone https://github.com/mutastic/mutastic.git
cd mutastic
just setup
just check
```

`just setup` turns on the commit message hook and installs the docs site's packages, once per
clone. `just check` runs everything a pull request needs to pass: compilation with every warning
an error, Error Prone and NullAway, the formatter, Checkstyle, the tests (end-to-end ones included,
which run real Gradle and Maven builds), the architecture rules and the coverage floor. It takes a
few minutes.

Useful along the way:

```sh
just fmt                                         # format the code
just test -p mutastic-cli --tests '*CommandTest' # one module's tests, or one class's
just run run samples/pricing                     # the mutastic command, built from here, run
```

[ARCHITECTURE.md](ARCHITECTURE.md) explains how the pieces fit, and `samples/` holds the small
projects the end-to-end tests run on.

## Commands

A job spelled in Gradle's words is spelled differently in the Rust edition, and again after a
change of build tool. So the everyday ones are recipes in the [justfile](justfile), a word each,
meaning the same in both editions: `just --list` shows them, `just check` runs everything a pull
request needs to pass, and `just ci` what CI runs. Each is one line over Gradle or a script, which
the justfile shows (`just check` is `./gradlew build`), so the work can still be done without it.
Call `./gradlew` directly only for a job no recipe covers.

CI calls the same recipes. Each job runs its part of `just ci` as a recipe in the `ci` group, such
as `just test-cli 2/4` for a quarter of the CLI's tests, and the corpus runs `just corpus`, so a
job that fails can be run here as CI ran it. Commits checks the justfile's format and that its
verbs are those [.github/just-verbs.txt](.github/just-verbs.txt) lists: after adding or removing a
recipe, run `just --summary | tr ' ' '\n' > .github/just-verbs.txt`.

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
  After changing an option, a command or what one prints, run `just docs-output`: it writes the
  docs' reference, which the build fails without, and the output the docs show.
- **The changelog:** a change someone using mutastic would notice gets an entry, as a file of its
  own in `changes/<section>/` ([changes/README.md](changes/README.md) has the sections and the
  shape). Don't edit `CHANGELOG.md`: a release gathers the files into it, and CI fails a pull
  request that edits it, as pull requests that all added to its end conflicted in the merge queue.
- **The HTML report:** the page both editions embed is `report-ui/`: its markup, styles and
  script in `src/`, and the fonts. After changing them, run `just report-ui` and commit `report.html`
  and its checksum with them; `node --test report-ui/test/*.test.mjs` renders it in Chrome (`just
  ci` runs it too). `just report-ui --embed mutation.json out.html` puts a run's report in it, to
  look at.
- **Dependencies:** pin exact versions, then `just lock`.

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
  table of error cases (`errorCases`) with the code and exit code each must end with, and the
  first line each says, word for word, where both editions say it alike;
- help's layout, the human summary's rows and their order, and the agent format's line shapes
  (its head, each entry, and the tail that closes the fence around the project's text);
- the events' key paths per event type and their order, and mutation.json's key paths;
- the events, report and SARIF schemas, by sha256;
- mutastic's own arid rules (`arid`): each id, the editions that have it, whether it judges a
  whole method or one site, the reasons it gives as each edition prints them, and a line of code
  it skips; the docs' list of them (`docs/src/data/arid.json`) is written from it;
- the decisions taken since 13 Nov 2026 (`decisions`), each a shared rule;
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
2. Add it here, run `just docs-output`, and `ContractTest` passes.
3. mutastic-rs pins the contract by commit, one file to a pull request in `contract/pins`, and
   reads it at the newest. When the Rust side is written, its contract check fails with "now
   matches". Take `rust` out of the pending entry in a mutastic PR, merge it, then pin that merge
   commit in the Rust PR (`scripts/pin-contract.sh <slug>` there). A pin to a mutastic PR's head,
   not yet merged, holds only until a later pin lands. Its weekly drift job, against mutastic
   main, says when the two are out of step.

An option only one edition will ever have, for a reason of its engine, is an `engine` difference
with its reason. A change to shared output (a summary row, an agent line) goes the same way: the
contract first, then each edition.

**Adding an arid rule:** give it an id in `AridRule` (`mutastic-api`), short and as people say
it, `mutastic:` and a plural noun where one fits; add it to the contract's `arid` with the edition
that has it, and its example to the engine's `truth/arid/Examples.java` in a method named for it.
`just docs-output` writes the docs' list. Another edition joins the rule
by adding itself to the rule's `editions`, `reasons` and `examples`, in a mutastic PR merged
back to back with its own. An id never changes once released.

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

## The corpus

The samples show one feature each. The bugs that matter turn up in the combinations people write:
a loop's slots read twice, a test helper in another package, a Maven wrapper older than 3.9.6. So
every night, and before every release, mutastic runs on a corpus of real projects,
[corpus/corpus.yaml](corpus/corpus.yaml), each pinned to a commit
([.github/workflows/corpus.yml](.github/workflows/corpus.yml)). Each project's job clones it, on
the JDK its entry names, runs its own tests, then a share of its mutants as events and again for
an agent, and passes when what the corpus expects of it holds: a score, the refusal it names, or
the known bug it fails with today. The run's page has a table of every project against the last
good night, and the run is red when a project that was good then fails now, or when a known bug's
project scores.

- **Adding a project:** pick one for a shape the others don't cover, and say which in `shapes`.
  Pin a commit, the whole hash. Set `jdk`, and `shard` so a share is 30 to 60 mutants. Set
  `expect` to `score`; to `refused:<code>` with a `note` saying why refusing it is right; or to
  `known:<bug>` with `fails:`, the code it fails with, and a `note`. Then run the workflow for it
  alone: `gh workflow run corpus.yml -f only=<name>`. The header of corpus.yaml says the same.
- **Fixing a known bug:** the night after the fix merges, its projects score, and the run says
  "now passes, update expect": change their `expect` to `score`, and drop `fails:`.
- **Every corpus failure becomes a sample or fixture.** The pull request that fixes one adds the
  smallest project that shows it, so the tests catch it from then on, not only the nightly.
- **Corpus projects are other people's code:** they're cloned, built and run in GitHub Actions
  only, with Docker stopped. `just corpus <name>` runs one as its job does, and on your machine it
  runs only an entry whose `git` is a `file://` URL, a fixture standing in for a project, from a
  corpus file `CORPUS_FILE` names.

## Pull requests

- One change per pull request, with a description of what it changes and why. The template asks.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org) and 50/72: a
  `type(scope): subject` of at most 50 characters, imperative and lower case, a blank line, then a
  body wrapped at 72 that explains why: what was wrong or missing, and what the change does about it.
- CI must pass. A pull request that changes the docs gets a preview link of its own. One that
  changes only what the build never reads (the Markdown pages at the top, the issue and pull
  request templates, workflows other than CI's, the hooks) skips the build and the tests; docs/
  is read by the build, so it doesn't. The merge queue runs everything, whatever changed. A new
  push cancels the checks still running for the one before.

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
`squash!` commits through as git writes them. `just setup` turns it on, once in each clone
(`git config core.hooksPath .githooks`, if that's all you want).

CI runs the same check on each new commit in a push or pull request (`.githooks/check-commits`),
counting from the commit that added the hook. `sh .githooks/test-commit-msg` tests the hook.

A pull request is squash-merged, and GitHub writes its title and " (#<number>)" as the subject on
`main`, where the check counts the whole line. So the title follows the same rules with the
suffix counted: at most 44 characters for #10 to #99, 43 for #100 to #999. CI checks it
(`.githooks/check-title`), again whenever the title is edited, and says the limit for the pull
request's number.

Pull requests land through a merge queue. A pull request that is green against an older `main`
can still turn `main` red once merged beside another one, so the queue tests each pull request
merged with the latest `main` and every pull request ahead of it, with the same checks, and
merges it only if that passes. One that fails there leaves the queue, and `main` stays green.

Merge with `just merge` (`.githooks/merge-pr`), not `gh pr merge` by hand. It writes the subject from the title,
takes the body from a file (wrapped at 72) or leaves it empty, checks the whole message with
`commit-msg`, and adds the pull request to the queue for the commit the checks ran on, once none has
failed: the queue takes it only once they all pass. The queue writes the squash commit from the
title and the pull request's description, so merge-pr makes the body the description first; don't
edit it after. In the queue, the `squash-title` check reads that commit, body and all. merge-pr
returns once the pull request is queued; with `--wait` it stays until it lands and prints the commit
on `main`, or says why it left the queue (run it with `--wait` again to wait for one already
queued). GitHub deletes the branch once it lands; local branches and worktrees are left alone.
With a queue, it waits only on the checks the rules require, as the queue does, so a check that
isn't required (a self-run share) doesn't stop it. `--admin` skips that wait; with a queue the pull
request still goes through it, as the rules let no one merge past it, and without one it merges
past the checks where the rules let an admin. The message is checked either way.

```sh
just merge 59                   # subject "<title> (#59)", no body; queued
just merge 59 body.txt          # with a body that says why
just merge 59 body.txt --wait   # and wait for it to land on main
```

## Licence

mutastic is under the [Apache License 2.0](LICENSE). Contributions are accepted under the same
licence: by opening a pull request, you agree yours is offered under it.
