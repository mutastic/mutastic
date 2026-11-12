package io.mutastic.cli;

import io.mutastic.api.BuildTool;
import io.mutastic.api.Comparison;
import io.mutastic.api.CoveragePass;
import io.mutastic.api.Listener;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Policy;
import io.mutastic.api.Project;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import io.mutastic.engine.ClassSources;
import io.mutastic.engine.Engine;
import io.mutastic.engine.OperatorSet;
import io.mutastic.engine.RunRecord;
import io.mutastic.engine.RunRecords;
import io.mutastic.engine.Shard;
import io.mutastic.report.AgentText;
import io.mutastic.report.Explanation;
import io.mutastic.report.HumanText;
import io.mutastic.report.Invocation;
import io.mutastic.report.JsonEvents;
import io.mutastic.report.MutantCommand;
import io.mutastic.report.NextText;
import io.mutastic.report.Queue;
import io.mutastic.report.ReportFiles;
import io.mutastic.report.SourceView;
import io.mutastic.report.Style;
import io.mutastic.report.Vocabulary;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/// `mutastic`: mutation testing for a Java build, read through Gradle or Maven. `mutastic --help`
/// lists every command and option ([Help]), from the one place each option is declared ([Options]).
///
/// The result goes to stdout, progress to stderr. A full run writes report files and keeps its
/// results in the module's work directory (`build/mutastic` or `target/mutastic`), and the next
/// full run compares against them: with the code unchanged, a mutant that changed result names a
/// flaky test.
public final class Main {
  /// The score reaches the threshold; for `verify`, the mutant is caught.
  static final int PASSED = 0;

  /// The score is below the threshold; for `verify`, the mutant isn't caught.
  static final int FAILED = 1;

  /// A usage error, or a build mutastic couldn't read.
  static final int USAGE = 2;

  /// The tests fail without any mutant: before the mutants, or a test that caught one, run again
  /// after them.
  static final int TESTS_FAIL = 3;

  /// mutastic itself went wrong.
  static final int INTERNAL = 4;

  private static final int DEFAULT_THRESHOLD = 80;

  /// `--tests-from=dependents`: every module that depends on the one run.
  static final String DEPENDENTS = "dependents";

  static final List<String> FORMATS =
      List.of("full", "brief", "concise", "grouped", "agent", "jsonl");

  /// The formats for people: they get each file's line as it finishes.
  private static final List<String> PEOPLE = List.of("full", "brief", "concise", "grouped");

  static final List<String> VOCABULARIES = List.of("plain", "classic");

  private static HumanText.Detail detail(String format) {
    return switch (format) {
      case "brief" -> HumanText.Detail.BRIEF;
      case "concise" -> HumanText.Detail.CONCISE;
      case "grouped" -> HumanText.Detail.GROUPED;
      default -> HumanText.Detail.FULL;
    };
  }

  private Main() {}

  public static void main(String[] args) {
    var terminal = Terminal.current();
    System.exit(run(args, utf8(System.out, terminal), utf8(System.err, terminal), terminal));
  }

  /// Output for a pipe or a file in UTF-8 on every platform, as agents, JSON readers and the other
  /// edition write it. Windows would write it in its legacy code page, which has no `✓`. A terminal
  /// keeps its own encoding, which is the one it shows, with an ASCII stand-in for what that can't
  /// show: a Windows console not set to UTF-8 would otherwise show `?`.
  private static PrintStream utf8(PrintStream stream, Terminal terminal) {
    if (stream.charset().equals(StandardCharsets.UTF_8)) {
      return stream;
    }
    return terminal.interactive()
        ? StandIns.over(stream)
        : new PrintStream(stream, true, StandardCharsets.UTF_8);
  }

  static int run(String[] args, PrintStream out, PrintStream err, Terminal terminal) {
    var answered = answeredWithoutRunning(List.of(args), out, err);
    if (answered.isPresent()) {
      return answered.getAsInt();
    }
    try {
      var command = Command.parse(args);
      return nothingToGate(command) ? PASSED : run(command, out, err, terminal);
    } catch (UsageException e) {
      err.print("mutastic: " + e.getMessage() + "\n");
      err.print(Help.usage());
      return USAGE;
    } catch (BuildTool.SetupFailed | Engine.UnknownMutant | Shares.Refused e) {
      err.print("mutastic: " + e.getMessage() + "\n");
      CodexSandbox.hint(e, terminal.env()).ifPresent(hint -> err.print(hint + "\n"));
      return USAGE;
    } catch (Engine.BaselineFailed e) {
      err.print("mutastic: " + e.getMessage() + "\n");
      return TESTS_FAIL;
    } catch (RuntimeException e) {
      err.print("mutastic: internal error: " + e + "\n");
      return INTERNAL;
    }
  }

  /// A hook runs in whatever project the agent is in. Where mutastic reads no build, there's
  /// nothing to gate: the hook says nothing and the agent goes on, so a plugin needn't know which
  /// builds mutastic reads. Run by a person, the same command says what's wrong.
  private static boolean nothingToGate(Command command) {
    return command.subcommand().gate()
        && !command.gating().hook().isEmpty()
        && BuildTools.find(command.directory().toAbsolutePath().normalize()).isEmpty();
  }

  private static int run(Command command, PrintStream out, PrintStream err, Terminal terminal) {
    var directory = command.directory().toAbsolutePath().normalize();
    var tool = BuildTools.of(directory);
    var lock = WorkLock.hold(tool.workDirectory(directory, command.module()), err);
    try {
      return held(command, output(command, out, err, terminal), tool, terminal);
    } finally {
      lock.close();
    }
  }

  /// A run, holding its module's work directory: see [WorkLock].
  private static int held(Command command, Output output, BuildTool tool, Terminal terminal) {
    // Shares that can't be put together are refused before the build is read.
    var shares = command.fromShards().map(directory -> Shares.read(directory, version()));
    var progress = output.progress();
    progress.started(version(), tool.name());
    var prepared = prepare(command, tool);
    showFileLines(command, output, humanText(command, output, prepared.sources()), prepared);
    var engine = new Engine(workerJar(), prepared.work().resolve("worker.log"));
    var options = options(command, terminal, prepared);
    var passes = new Passes(engine, options, progress, tool, shares);
    if (command.subcommand() != Subcommand.RUN) {
      return command.subcommand().gate()
          ? gated(new Gated(command, prepared, passes, output, terminal))
          : single(command, prepared, passes, output);
    }
    return fullRun(command, prepared, passes, output);
  }

  /// `run`: every mutant, or with `--shard` a share of them, or with `--from-shards` the shares'
  /// results put together; then the result, its record and its reports.
  private static int fullRun(Command command, Prepared prepared, Passes passes, Output output) {
    var display =
        display(command, prepared, output, humanText(command, output, prepared.sources()));
    command.shard().ifPresent(shard -> Shares.forget(shareEvents(prepared, shard)));
    var tested = passes.run(command, prepared, display.listener());
    passes.progress().finished();
    var kept = kept(command, prepared, tested);
    display.printer().print(tested.report(), kept.comparison(), kept.written());
    baselineNote(command, prepared, tested.report(), output);
    return exitCode(command, tested.report());
  }

  /// `baseline`: every mutant no test catches, written for the project to commit; or with
  /// `--prune`, the baseline's mutants the tests catch now dropped. Never rewritten whole once
  /// there, as taking in new misses would hide them.
  private static int baseline(Command command, Prepared prepared, Passes passes, PrintStream out) {
    var directory = prepared.directory();
    boolean prune = command.ignore().map(Ignoring::prune).orElse(false);
    if (prune != Baseline.exists(directory)) {
      out.print(
          (prune
                  ? "mutastic: there's no baseline to prune: `mutastic baseline` writes one"
                  : ("mutastic: there's a baseline already, in %s. `mutastic baseline --prune`"
                          + " drops its mutants the tests catch now; it's never rewritten to take"
                          + " in new misses, which would hide them")
                      .formatted(Baseline.FILE))
              + "\n");
      return FAILED;
    }
    var report = passes.run(command, prepared, passes.progress()).report();
    passes.progress().finished();
    out.print((prune ? pruned(directory, report) : baselined(prepared, report)) + "\n");
    return PASSED;
  }

  private static String pruned(Path directory, Report report) {
    int dropped = Baseline.prune(directory, caughtNow(report, Baseline.ids(directory)));
    return "mutastic: %d of the baseline's mutants are caught now, and are dropped from %s"
        .formatted(dropped, Baseline.FILE);
  }

  private static String baselined(Prepared prepared, Report report) {
    var entries = Baseline.entries(report.results(), prepared.sources());
    Baseline.write(prepared.directory(), entries);
    return ("mutastic: %d mutants no test catches are in %s: commit it. A miss that isn't in it is"
            + " new: the gate holds only new misses, and next offers them first")
        .formatted(entries.size(), Baseline.FILE);
  }

  /// The baseline's mutants a run doesn't find uncaught: caught now, ignored, or gone with their
  /// code.
  private static java.util.Set<String> caughtNow(Report report, java.util.Set<String> baseline) {
    var uncaught = Passes.uncaught(report);
    return baseline.stream()
        .filter(id -> !uncaught.contains(id))
        .collect(java.util.stream.Collectors.toSet());
  }

  /// After a full run, how many of the baseline's mutants it found caught: they can be pruned.
  private static void baselineNote(
      Command command, Prepared prepared, Report report, Output output) {
    var baseline = Baseline.ids(prepared.directory());
    if (baseline.isEmpty()
        || report.scope().isPresent()
        || !command.only().isEmpty()
        || output.format().equals("jsonl")) {
      return;
    }
    int caught = caughtNow(report, baseline).size();
    if (caught > 0) {
      output
          .out()
          .printf(
              "note: %d of the baseline's mutants are caught now: `mutastic baseline --prune` drops"
                  + " them\n",
              caught);
    }
  }

  /// `next`: the head of the queue, or with `--top` the first few, as the format has them. Exits 1
  /// while something is queued, so a loop can run it until it's done. A mutant proposed for
  /// ignoring waits on a person, not a test: it's counted, not queued.
  private static int next(Command command, Prepared prepared, Passes passes, Output output) {
    var human = humanText(command, output, prepared.sources());
    var legacy = Baseline.ids(prepared.directory());
    var proposed = proposedIds(prepared.directory());
    var queue = queued(command, prepared, passes).map(report -> Queue.of(report, legacy, proposed));
    passes.progress().finished();
    var shown = queue.orElseGet(() -> Queue.empty(legacy));
    var project = prepared.project().name();
    var text = new NextText(prepared.sources(), human, output.style(), output.words());
    switch (output.format()) {
      case "jsonl" ->
          new JsonEvents(output.out(), prepared.sources(), command.threshold())
              .next(shown, command.top());
      case "agent" -> output.out().print(text.agent(shown, project));
      default -> output.out().print(text.human(shown, project, command.top()));
    }
    return shown.isEmpty() ? PASSED : FAILED;
  }

  /// The run `next` ranks: while the code under test, the operators, the scope and mutastic are as
  /// they were, only the mutants the last `next` left uncaught, since a test written since can only
  /// catch some of them; every mutant otherwise. Empty when the last left none, as nothing can have
  /// made a new one. A pass over every mutant is kept as the last run, as `run`'s is: `explain`
  /// reads it.
  private static Optional<Report> queued(Command command, Prepared prepared, Passes passes) {
    var key = nextKey(command, prepared);
    var last = NextRun.read(prepared.work(), key);
    if (last.isPresent() && last.get().queued().isEmpty()) {
      return Optional.empty();
    }
    var run =
        last.map(
                found ->
                    new Passes(
                        passes.engine(),
                        passes.options().among(found.queued()),
                        passes.progress(),
                        passes.tool(),
                        Optional.empty()))
            .orElse(passes);
    var tested = run.run(command, prepared, passes.progress());
    var report = tested.report();
    if (last.isEmpty()) {
      recorded(command, prepared, report, tested.otherTests());
    }
    new NextRun(key, Passes.uncaught(report)).write(prepared.work());
    return Optional.of(report);
  }

  /// What a queue was found under: the code under test, the operators, the scope and mutastic.
  private static String nextKey(Command command, Prepared prepared) {
    return NextRun.keyOf(
        RunRecord.classesOf(prepared.project()),
        OperatorSet.active(policy(command, prepared)).stream()
            .map(io.mutastic.api.Operator::id)
            .sorted()
            .collect(java.util.stream.Collectors.joining(",")),
        command.since().orElse("-"),
        RunRecord.toolOf(List.of(codeSource(Engine.class), workerJar())));
  }

  /// A command but `run` and `gate`: `next` and `baseline` over the module, or one mutant's.
  private static int single(Command command, Prepared prepared, Passes passes, Output output) {
    return switch (command.subcommand()) {
      case NEXT -> next(command, prepared, passes, output);
      case BASELINE -> baseline(command, prepared, passes, output.out());
      default -> oneMutant(command, prepared, passes, output);
    };
  }

  /// `verify`, which runs one mutant's tests and says whether it's caught; or `ignore`, which runs
  /// none and records a decision.
  private static int oneMutant(Command command, Prepared prepared, Passes passes, Output output) {
    var out = output.out();
    if (command.explain()) {
      return explained(command, prepared, passes, output);
    }
    if (command.ignore().isPresent()) {
      return ignored(command, prepared, passes, out);
    }
    var report = passes.run(command, prepared, passes.progress()).report();
    passes.progress().finished();
    int exit = verified(report.results(), out);
    if (report.failingAlone().isEmpty()) {
      return exit;
    }
    out.print(AgentText.failingAlone(report));
    return TESTS_FAIL;
  }

  /// `ignore`: records, proposes, accepts or removes a decision; runs no tests.
  private static int ignored(Command command, Prepared prepared, Passes passes, PrintStream out) {
    var where =
        new IgnoreCommand.Where(
            prepared.directory(),
            prepared.sources(),
            shown(prepared.directory().resolve(Ignores.FILE)));
    // Removing or accepting needs no mutant: its code may be gone, and a proposal says the rest.
    var how = command.ignore().get();
    if (how.accept()) {
      return IgnoreCommand.accept(command.only().getFirst(), where, out);
    }
    return how.remove()
        ? IgnoreCommand.remove(command.only().getFirst(), where, out)
        : IgnoreCommand.run(
            command.ignore().get(),
            where,
            passes.engine().find(prepared.project(), passes.options()),
            out);
  }

  /// Only full runs compare, are kept and write reports: one mutant says nothing about the rest.
  private static Kept kept(Command command, Prepared prepared, Tested tested) {
    return command.only().isEmpty()
        ? keep(command, prepared, tested.report(), tested.otherTests())
        : Kept.NOTHING;
  }

  /// A `gate` command, with what running it needs.
  private record Gated(
      Command command, Prepared prepared, Passes passes, Output output, Terminal terminal) {
    boolean hooked() {
      return !command.gating().hook().isEmpty();
    }

    Path work() {
      return prepared.work();
    }
  }

  /// `gate open`, `gate check` or `gate close` (see [Gate]).
  private static int gated(Gated gated) {
    var session = gated.hooked() ? Gate.session(stdin()) : "";
    return switch (gated.command().subcommand()) {
      case GATE_OPEN -> gateOpen(gated, session);
      case GATE_CLOSE -> {
        boolean closed = Gate.State.close(gated.work());
        if (!gated.hooked()) {
          gated
              .output()
              .out()
              .print(closed ? "mutastic gate: closed\n" : "mutastic gate: none open here\n");
        }
        yield PASSED;
      }
      default -> gateCheck(gated);
    };
  }

  /// Keeps where the task starts; with a hook, once for each session. A hook's output is the
  /// agent's to read: it's told the task is gated, and how to check its work as it goes.
  private static int gateOpen(Gated gated, String session) {
    if (gated.hooked() && openFor(gated, session)) {
      return PASSED;
    }
    var directory = gated.prepared().directory();
    var base = new Git(directory).head();
    var shape = Gate.Shape.of(gated.prepared().project().testSources());
    new Gate.State(session, base, ignored(directory), shape, 0, "", Map.of()).write(gated.work());
    gated
        .output()
        .out()
        .printf(
            "mutastic gate: this task is gated from %s: before you finish, check the lines it"
                + " changes with `mutastic run --since=%s --format=agent`; %s. %s\n",
            base.substring(0, Math.min(7, base.length())),
            base,
            whenItsDone(gated),
            MutantCommand.NO_REWRITE);
    return PASSED;
  }

  /// A hook checks the task when the agent finishes, so the agent isn't asked to check it too: a
  /// check of its own would run the same mutants twice.
  private static String whenItsDone(Gated gated) {
    return gated.hooked()
        ? "the gate checks them when you finish, so you needn't run `mutastic gate check` yourself"
        : "`mutastic gate check` says when it's done";
  }

  /// Whether a task is open already for this session.
  private static boolean openFor(Gated gated, String session) {
    return !session.isEmpty()
        && Gate.State.read(gated.work())
            .filter(state -> state.session().equals(session))
            .isPresent();
  }

  /// Whether the task is done: as a hook's answer, or as the exit status, with what's left.
  private static int gateCheck(Gated gated) {
    var open = Gate.State.read(gated.work());
    if (open.isEmpty()) {
      if (!gated.hooked()) {
        gated
            .output()
            .out()
            .print("mutastic gate: none open here: `mutastic gate open` starts one\n");
      }
      return gated.hooked() ? PASSED : USAGE; // nothing gated: a hooked agent may stop
    }
    var verdict = gateVerdict(gated, open.get());
    var state = open.get().remembering(verdict.red());
    if (verdict.passes()) {
      state.passed().write(gated.work());
      return answer(gated, forThePerson(gated, state, verdict));
    }
    return notDone(gated, state, verdict);
  }

  /// The agent is sent back, unless, as a hook, it changed nothing since it last was, or it has had
  /// its tries: then it's let stop, and the person told what's left.
  private static int notDone(Gated gated, Gate.State open, Gate.Verdict verdict) {
    var tree = new Git(gated.prepared().directory()).tree();
    var state = open.blockedOnce(tree);
    int tries = gated.command().gating().tries();
    var answer = verdict;
    if (gated.hooked() && open.unchangedSinceBlocked(tree)) {
      answer = new Gate.Verdict(true, true, declined(verdict.text()));
    } else if (gated.hooked() && state.blocks() > tries) {
      answer = new Gate.Verdict(true, true, gaveUp(tries, verdict.text()));
    }
    (answer.passes() ? state.passed() : state).write(gated.work());
    return answer(gated, answer.passes() ? forThePerson(gated, open, answer) : answer);
  }

  /// A pass or a hand-over, with the tests there when the task started that it changed what they
  /// expect: the person's to check, since the score can't.
  private static Gate.Verdict forThePerson(Gated gated, Gate.State state, Gate.Verdict verdict) {
    var changed = Gate.changedExpectations(gated.prepared().project().testSources(), state.base());
    if (changed.isEmpty()) {
      return verdict;
    }
    return new Gate.Verdict(
        true,
        verdict.gaveUp(),
        ("%s\n\nThe task changed what tests that were there when it started expect, in %s: check"
                + " that's what you asked for")
            .formatted(verdict.text(), String.join(", ", changed)));
  }

  /// Sent back, the agent changed nothing: it's declined, as it may when told to, so sending it
  /// back again only spends its turns.
  private static String declined(String left) {
    return "mutastic gate: nothing changed since the agent was sent back, so it's over to you. %s"
        .formatted(left);
  }

  private static String gaveUp(int tries, String left) {
    return "mutastic gate: the agent was sent back %d times, so it's over to you. %s"
        .formatted(tries, left);
  }

  /// The verdict, as the hook's answer, or printed, with the exit status.
  private static int answer(Gated gated, Gate.Verdict verdict) {
    var out = gated.output().out();
    if (gated.hooked()) {
      out.print(Gate.claudeHook(verdict) + "\n");
      return PASSED;
    }
    out.print(verdict.text() + "\n");
    return verdict.passes() ? PASSED : FAILED;
  }

  /// What the gate says of the task: done, or what's left.
  private static Gate.Verdict gateVerdict(Gated gated, Gate.State state) {
    var directory = gated.prepared().directory();
    var gone = goneRed(gated, state);
    if (gone.isPresent()) {
      return gone.get(); // first: taking the test away may leave no line changed
    }
    if (new Git(directory).changedSince(state.base()).lines().isEmpty()) {
      return Gate.Verdict.pass("mutastic gate: passed: the task changed no lines mutastic mutates");
    }
    return selfIgnored(directory, state)
        .or(() -> selfBaselined(directory, state))
        .or(() -> fewerTests(gated, state))
        .orElseGet(() -> mutantsVerdict(gated, state));
  }

  /// An ignore the task made itself: a person's call, so it's proposed instead.
  private static Optional<Gate.Verdict> selfIgnored(Path directory, Gate.State state) {
    var added = new java.util.TreeSet<>(ignored(directory));
    added.removeAll(state.ignored());
    return added.isEmpty()
        ? Optional.empty()
        : Optional.of(
            Gate.Verdict.block(
                ("mutastic gate: the task ignored %s itself, and whether a mutant is worth a test"
                        + " is a person's call. Take it back with `mutastic ignore --remove ID`, and"
                        + " propose it instead: `mutastic ignore ID --propose --as=KIND"
                        + " --reason=\"…\"`")
                    .formatted(String.join(", ", added))));
  }

  /// A test that failed at an earlier check and is gone now, by name and by body: a task isn't done
  /// by taking away the test that says it's wrong.
  private static Optional<Gate.Verdict> goneRed(Gated gated, Gate.State state) {
    if (state.red().isEmpty()) {
      return Optional.empty();
    }
    var found = RedTests.Found.in(gated.prepared().project().testSources());
    var gone = RedTests.gone(state.red(), found);
    return gone.isEmpty()
        ? Optional.empty()
        : Optional.of(
            Gate.Verdict.block(
                ("mutastic gate: %s failed at an earlier check, and %s gone now. Put %s back: the"
                        + " gate doesn't pass a task by taking a failing test away. Leave a failing"
                        + " test in place and make the code pass it, unless the spec or plan is"
                        + " what's wrong, in which case say so and stop")
                    .formatted(
                        String.join(", ", gone),
                        gone.size() == 1 ? "it's" : "they're",
                        gone.size() == 1 ? "it" : "them")));
  }

  /// A mutant the task added to the baseline itself: like an ignore, a person's call.
  private static Optional<Gate.Verdict> selfBaselined(Path directory, Gate.State state) {
    var added = new java.util.TreeSet<>(Baseline.ids(directory));
    added.removeAll(Baseline.idsAt(directory, state.base()));
    return added.isEmpty()
        ? Optional.empty()
        : Optional.of(
            Gate.Verdict.block(
                ("mutastic gate: the task added %s to %s itself, and whether a miss is the"
                        + " project's backlog is a person's call. Take %s out, and write the test,"
                        + " or propose ignoring it: `mutastic ignore ID --propose --as=KIND"
                        + " --reason=\"…\"`")
                    .formatted(
                        String.join(", ", added),
                        Baseline.FILE,
                        added.size() == 1 ? "it" : "them")));
  }

  /// Fewer tests running than when the task started: taken away, or disabled.
  private static Optional<Gate.Verdict> fewerTests(Gated gated, Gate.State state) {
    var before = state.shape();
    var now = Gate.Shape.of(gated.prepared().project().testSources());
    return !now.fewerThan(before)
        ? Optional.empty()
        : Optional.of(
            Gate.Verdict.block(
                ("mutastic gate: fewer tests run than when the task started (%d, %d disabled; now"
                        + " %d, %d disabled). Put them back: the gate doesn't pass a change by"
                        + " taking tests away")
                    .formatted(before.tests(), before.disabled(), now.tests(), now.disabled())));
  }

  /// The mutants on the lines the task changed: each caught, or proposed for a person to ignore.
  private static Gate.Verdict mutantsVerdict(Gated gated, Gate.State state) {
    Report report;
    try {
      report = gateRun(gated, state.base());
    } catch (Engine.BaselineFailed e) {
      var testSources = gated.prepared().project().testSources();
      return Gate.Verdict.red(red(gated, state, e), RedTests.remember(e.failing(), testSources));
    }
    return report.failingAlone().isEmpty()
        ? missedVerdict(gated, report)
        : Gate.Verdict.block(falseCaught(report));
  }

  /// Done when each mutant on the changed lines is caught, or proposed for a person to ignore.
  private static Gate.Verdict missedVerdict(Gated gated, Report report) {
    var directory = gated.prepared().directory();
    var proposed = proposedIds(directory);
    var older = Baseline.ids(directory);
    var missed = missedIds(report).stream().filter(id -> !older.contains(id)).toList();
    var open = missed.stream().filter(id -> !proposed.contains(id)).toList();
    if (open.isEmpty()) {
      return Gate.Verdict.pass(passed(report, missed, missedIds(report).size() - missed.size()));
    }
    // The cards are for what holds the gate: a proposed mutant waits on a person, not the agent.
    var shown =
        report.results().stream()
            .filter(each -> !older.contains(each.mutant().id()))
            .filter(each -> !proposed.contains(each.mutant().id()) || !open(each))
            .toList();
    var waiting = missed.stream().filter(proposed::contains).toList();
    return Gate.Verdict.block(
        "%s\n\n%s"
            .formatted(
                heldBy(
                    withStatus(shown, Status.MISSED),
                    withStatus(shown, Status.NOT_COVERED),
                    waiting),
                cards(gated, report.withResults(shown))));
  }

  /// Whether a mutant no test catches: missed, or reached by none.
  private static boolean open(MutantResult result) {
    return result.status() == Status.MISSED || result.status() == Status.NOT_COVERED;
  }

  private static List<String> withStatus(List<MutantResult> results, Status status) {
    return results.stream()
        .filter(each -> each.status() == status)
        .map(each -> each.mutant().id())
        .toList();
  }

  /// What holds the gate, every mutant by id: those missed, those no test reaches, and apart from
  /// them those proposed, which wait on a person, not the agent.
  static String heldBy(List<String> missed, List<String> uncovered, List<String> waiting) {
    int count = missed.size() + uncovered.size();
    var parts = new ArrayList<String>();
    if (!missed.isEmpty()) {
      parts.add("missed: " + String.join(", ", missed));
    }
    if (!uncovered.isEmpty()) {
      parts.add("no test reaches: " + String.join(", ", uncovered));
    }
    return ("mutastic gate: %d mutant%s on the lines the task changed %s it, so it isn't done (%s).%s"
            + " For each card: write the test, and run its `done when` before you move on; for a"
            + " line no test reaches, write a test that reaches it; if a case can't happen,"
            + " propose ignoring it, for a person to accept. "
            + MutantCommand.NO_REWRITE)
        .formatted(
            count,
            count == 1 ? "" : "s",
            count == 1 ? "holds" : "hold",
            String.join("; ", parts),
            waiting.isEmpty()
                ? ""
                : " Proposed, waiting on a person, and not holding it: %s."
                    .formatted(String.join(", ", waiting)));
  }

  /// Tests that caught mutants and then failed on their own with no mutant in: their catches don't
  /// count, and the task isn't done while they do.
  private static String falseCaught(Report report) {
    return "mutastic gate: a test that caught mutants on the lines the task changed fails on its"
        + " own, with no mutant in, so its catches don't count and the task isn't done.\n\n%s"
            .formatted(AgentText.failingAlone(report).strip());
  }

  /// Tests that fail before any mutant. One that was there when the task started is most likely
  /// broken by the change, and rewriting what it expects would pass a wrong change: the agent is
  /// told to find out which is wrong, and to ask. One the task added usually needs its code
  /// written, but it may be what shows the spec is wrong, and making it pass would bury that.
  private static String red(Gated gated, Gate.State state, Engine.BaselineFailed failed) {
    var before =
        failed.failing().stream().filter(test -> wasThere(gated, state.base(), test)).toList();
    if (before.isEmpty()) {
      return "mutastic gate: "
          + failed.getMessage()
          + ". Make them pass first, unless the spec or plan is what's wrong, in which case say so"
          + " and stop. Either way, leave a failing test in place: the gate doesn't pass a task"
          + " by taking it away";
    }
    return ("mutastic gate: %s.\n\n%s %s there when the task started: if %s passed then, the task's"
            + " change broke %s. Find out whether the change or the test is wrong. Don't change"
            + " what a test expects, or take it away, to make it pass unless the task asked for"
            + " that behaviour, and if you're not sure, ask")
        .formatted(
            failed.getMessage(),
            String.join(", ", before),
            before.size() == 1 ? "was" : "were",
            before.size() == 1 ? "it" : "they",
            before.size() == 1 ? "it" : "them");
  }

  /// Whether a test, `com.example.ATest#name`, was in its class's source at `base`.
  private static boolean wasThere(Gated gated, String base, String test) {
    var type = test.replaceFirst("[#$].*", "");
    var method = test.replaceFirst("^[^#]*#?", "").replaceFirst("[(\\[].*", "");
    for (var sources : gated.prepared().project().testSources()) {
      for (var extension : List.of(".java", ".kt")) {
        var source = new Git(sources).at(base, Path.of(type.replace('.', '/') + extension));
        if (source.isPresent() && source.get().contains(method)) {
          return true;
        }
      }
    }
    return false;
  }

  /// The run on the lines changed since `base`.
  private static Report gateRun(Gated gated, String base) {
    var run = gated.command().runSince(base);
    var passes = gated.passes();
    var options = options(run, gated.terminal(), gated.prepared());
    var report =
        new Passes(passes.engine(), options, passes.progress(), passes.tool(), Optional.empty())
            .run(run, gated.prepared(), passes.progress())
            .report();
    passes.progress().finished();
    return report;
  }

  /// What a pass says: how many the tests catch, and any proposals waiting for a person.
  private static String passed(Report report, List<String> waiting, int older) {
    return ("mutastic gate: passed: the tests catch %d of the %d mutants on the lines the task"
            + " changed%s%s")
        .formatted(
            report.count(Status.CAUGHT) + report.count(Status.TIMED_OUT),
            report.results().size() - report.count(Status.SKIPPED),
            waiting.isEmpty()
                ? ""
                : "; the rest wait for a person, proposed in .mutastic/proposals.yaml: "
                    + String.join(", ", waiting),
            older == 0
                ? ""
                : "; %d, missed before the task and in %s, %s the project's backlog, not the task's"
                    .formatted(older, Baseline.FILE, older == 1 ? "is" : "are"));
  }

  /// The missed mutants' cards, as the agent format has them, without its verdict line.
  private static String cards(Gated gated, Report report) {
    return new AgentText(gated.prepared().sources(), 0, gated.output().words())
        .render(report, Optional.empty(), false)
        .lines()
        .dropWhile(line -> line.startsWith("mutastic: "))
        .collect(java.util.stream.Collectors.joining("\n"));
  }

  /// The mutants no test catches: missed, or reached by none.
  private static List<String> missedIds(Report report) {
    return report.results().stream()
        .filter(each -> each.status() == Status.MISSED || each.status() == Status.NOT_COVERED)
        .map(each -> each.mutant().id())
        .toList();
  }

  private static java.util.Set<String> proposedIds(Path directory) {
    return Ignores.proposed(directory).stream()
        .map(entry -> entry.ignore().id())
        .collect(java.util.stream.Collectors.toSet());
  }

  /// The ids the project ignores.
  private static java.util.Set<String> ignored(Path directory) {
    return Ignores.read(directory).stream()
        .map(entry -> entry.ignore().id())
        .collect(java.util.stream.Collectors.toSet());
  }

  /// A hook's input: what the agent says of the event, as JSON.
  private static String stdin() {
    try {
      return new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      return "";
    }
  }

  /// `explain`: everything known of one mutant, with how the last full run went.
  private static int explained(Command command, Prepared prepared, Passes passes, Output output) {
    var lastRun = lastRun(prepared, command.only().getFirst());
    var report = passes.run(command, prepared, passes.progress()).report();
    passes.progress().finished();
    var result = report.results().getFirst();
    output.out().print(humanText(command, output, prepared.sources()).explained(result));
    output
        .out()
        .print(
            new Explanation(prepared.sources(), output.style()).render(result.mutant(), lastRun));
    return PASSED;
  }

  /// How the last full run of this project with the mutant `id` went for it, if there was one.
  private static Optional<Explanation.LastRun> lastRun(Prepared prepared, String id) {
    return new RunRecords(prepared.work())
        .newestFirst().stream()
            .filter(record -> record.isOf(prepared.project()))
            .flatMap(record -> Optional.ofNullable(record.results().get(id)).stream())
            .findFirst()
            .map(entry -> new Explanation.LastRun(entry.status(), entry.caughtBy()));
  }

  /// `ignore`'s own: `--prune` what no longer holds, `--remove` one by id, or else ignore one, for
  /// `reason`, as the sort of decision `kind` says (`--as`, empty when not given).
  ///
  /// `--propose` records it as waiting for a person, `--accept` makes a proposal an ignore.
  /// `gate`'s own: `--hook=claude` to answer as that agent's hook, empty for a person; and how many
  /// times `check` sends a hooked agent back before it lets it stop.
  record Gating(String hook, int tries) {}

  /// How a caught mutant's tests run: each catch run once more to confirm it (`--confirm-catches`),
  /// and every covering test run, not only up to the first catch (`--kill-matrix`).
  record Catches(boolean confirm, boolean every) {}

  /// A run split across machines: the share this one runs (`--shard`), or the directory of the
  /// shares it's made of (`--from-shards`).
  record Sharing(Optional<Shard> shard, Optional<Path> from) {
    static final Sharing NONE = new Sharing(Optional.empty(), Optional.empty());
  }

  record Ignoring(
      boolean prune, boolean remove, String reason, String kind, boolean propose, boolean accept) {}

  /// A file as the person would type it from where mutastic ran: relative if it's below, else
  /// absolute.
  private static String shown(Path file) {
    var here = Path.of("").toAbsolutePath().normalize();
    var absolute = file.toAbsolutePath().normalize();
    return absolute.startsWith(here) ? slashed(here.relativize(absolute)) : absolute.toString();
  }

  /// A relative path with `/` between its parts, as every platform's shells take it, so what
  /// mutastic prints reads the same on Windows as elsewhere.
  static String slashed(Path relative) {
    return relative.toString().replace(java.io.File.separatorChar, '/');
  }

  /// Each source file's line as it finishes, for people. With other modules' tests, a mutant's
  /// result is known only once they've run too, so not then.
  private static void showFileLines(
      Command command, Output output, HumanText human, Prepared prepared) {
    if (PEOPLE.contains(output.format()) && command.testsFrom().isEmpty()) {
      output
          .progress()
          .showFiles(results -> human.fileFinished(prepared.project().name(), results));
    }
  }

  /// The merged result, and the other modules' test directories that ran.
  private record Tested(Report report, List<Path> otherTests) {}

  /// The run: the module's own tests, then each `--tests-from` module's over the mutants still
  /// uncaught, as that module's own build runs them (its classpath, with this module's mutated
  /// classes in place of its jar), merged.
  ///
  /// @param shares with `--from-shards`, the shares whose results are the run's, run by no test
  ///   here
  private record Passes(
      Engine engine,
      Engine.Options options,
      Progress progress,
      BuildTool tool,
      Optional<Shares> shares) {
    /// The module's own tests, then each `--tests-from` module's over what they missed: for a full
    /// run, and for one mutant (`verify`, `explain`) alike, so a mutant only another module's tests
    /// catch is caught either way. With `--from-shards`, the shares' results.
    Tested run(Command command, Prepared prepared, Listener listener) {
      if (shares.isPresent()) {
        return new Tested(fromShards(shares.get(), prepared, listener), List.of());
      }
      var merged = engine.run(prepared.project(), options, listener);
      var otherTests = new ArrayList<Path>();
      for (String module : modules(command, prepared)) {
        var uncaught = uncaught(merged);
        if (uncaught.isEmpty()) {
          break;
        }
        var runner = runner(prepared, module);
        otherTests.addAll(runner.testClasses());
        progress.testsFrom(module);
        var pass =
            engine.run(
                prepared.project().runningTestsOf(runner, prepared.directory()),
                options.among(uncaught),
                counting());
        merged = merged.merge(pass);
      }
      return new Tested(merged, otherTests);
    }

    /// The whole run, of the shares' results: the mutants found as a run finds them, none run.
    private Report fromShards(Shares from, Prepared prepared, Listener listener) {
      progress.note(from.summary());
      var project = prepared.project();
      return from.whole(
          project, RunRecord.codeOf(project), engine.find(project, options), listener);
    }

    /// The `--tests-from` modules, with `dependents` as the modules that depend on this one.
    private List<String> modules(Command command, Prepared prepared) {
      var modules = new java.util.LinkedHashSet<String>();
      for (String named : command.testsFrom()) {
        if (!named.equals(DEPENDENTS)) {
          modules.add(named);
          continue;
        }
        var found =
            tool.dependents(
                prepared.directory(), command.module(), prepared.work().resolve("dependents"));
        if (found.isEmpty()) {
          progress.note(
              "no module depends on %s: only its own tests ran".formatted(command.module()));
        }
        modules.addAll(found);
      }
      return List.copyOf(modules);
    }

    /// The other module, read into a directory of its own below this module's.
    private Project runner(Prepared prepared, String module) {
      var work =
          prepared.work().resolve("tests-from").resolve(module.substring(1).replace(':', '/'));
      return tool.read(prepared.directory(), module, work, prepared.directory().toString());
    }

    private static java.util.Set<String> uncaught(Report report) {
      return report.results().stream()
          .filter(result -> !result.status().detected() && result.status() != Status.SKIPPED)
          .map(result -> result.mutant().id())
          .collect(java.util.stream.Collectors.toSet());
    }

    /// Progress only: each result is shown once every module's tests have run.
    private Listener counting() {
      return new Listener() {
        @Override
        public void coverageFinished(CoveragePass found) {
          progress.coverageFinished(found);
        }

        @Override
        public void mutantFinished(MutantResult result) {
          progress.mutantFinished(result);
        }
      };
    }
  }

  /// Help and the version, answered before any command reads a build; and, with no command at all,
  /// help as the error. Empty when there's a command to run.
  private static java.util.OptionalInt answeredWithoutRunning(
      List<String> args, PrintStream out, PrintStream err) {
    var asked = helpAsked(args);
    if (asked.isPresent()) {
      out.print(asked.get());
      return java.util.OptionalInt.of(PASSED);
    }
    if (args.isEmpty()) {
      err.print(Help.general(version()));
      return java.util.OptionalInt.of(USAGE);
    }
    return java.util.OptionalInt.empty();
  }

  /// What help was asked for, if any: `--version`; `--help`, `-h` or `help` for every command;
  /// `<command> --help` or `help <command>` for one.
  private static Optional<String> helpAsked(List<String> words) {
    if (words.isEmpty()) {
      return Optional.empty();
    }
    if (words.getFirst().equals("--version")) {
      return Optional.of("mutastic " + version() + "\n");
    }
    boolean help = words.getFirst().equals("help");
    if (!help && !words.contains("--help") && !words.contains("-h")) {
      return Optional.empty();
    }
    var command = Subcommand.at(help ? words.subList(1, words.size()) : words);
    return Optional.of(
        command.map(each -> Help.of(each, version())).orElseGet(() -> Help.general(version())));
  }

  /// Where and how the result is shown: stdout and progress, the format, the words for results and
  /// the terminal's style.
  private record Output(
      PrintStream out, Progress progress, String format, Vocabulary words, Style style) {}

  private static Output output(
      Command command, PrintStream out, PrintStream err, Terminal terminal) {
    var format = command.format().orElse(terminal.defaultFormat());
    if (!FORMATS.contains(format)) {
      throw new UsageException(
          "unknown format %s in MUTASTIC_FORMAT (one of %s)"
              .formatted(format, String.join(", ", FORMATS)));
    }
    var vocabulary = command.vocabulary().orElse(terminal.defaultVocabulary());
    if (!VOCABULARIES.contains(vocabulary)) {
      throw new UsageException(
          "unknown vocabulary %s in MUTASTIC_VOCABULARY (one of plain, classic)"
              .formatted(vocabulary));
    }
    var words = Vocabulary.valueOf(vocabulary.toUpperCase(java.util.Locale.ROOT));
    var progress = new Progress(err, progressMode(command.progress(), format, terminal));
    return new Output(out, progress, format, words, style(command, terminal));
  }

  private static Style style(Command command, Terminal terminal) {
    var style = terminal.colour(command.colour()) ? Style.COLOUR : Style.PLAIN;
    style = terminal.links(command.links()) ? style.withLinks() : style;
    return style.withLabels(labels(terminal.defaultLabels()));
  }

  private static Style.Labels labels(String chosen) {
    return switch (chosen) {
      case "square" -> Style.Labels.SQUARE;
      case "rounded" -> Style.Labels.ROUNDED;
      case "off" -> Style.Labels.OFF;
      default ->
          throw new UsageException(
              "unknown labels %s in MUTASTIC_PILLS (one of square, rounded, off)"
                  .formatted(chosen));
    };
  }

  /// A project read through its Gradle build: where it is, where mutastic keeps its files, its code
  /// as the output shows it, and when and how long reading it took.
  private record Prepared(
      Path directory,
      Path work,
      Project project,
      SourceView sources,
      LocalTime startedAt,
      Duration setup) {}

  private static Prepared prepare(Command command, BuildTool tool) {
    long start = System.nanoTime();
    var startedAt = LocalTime.now(java.time.ZoneId.systemDefault()); // the reader's clock
    var directory = command.directory().toAbsolutePath().normalize();
    var work = workDirectory(tool, directory, command.module(), command.suite());
    var here = Path.of("").toAbsolutePath().normalize();
    var target = withSuite(target(here, directory, command.module()), command.suite());
    var project =
        command.setup().isPresent()
            ? tool.read(directory, command.module(), command.setup().get())
            : tool.read(
                new BuildTool.Request(
                    directory,
                    command.module(),
                    work,
                    String.join(" ", target(here, directory, ":")),
                    command.suite()));
    project = project.withTests(project.tests().leavingOut(command.excludeTests()));
    var setup = Duration.ofNanos(System.nanoTime() - start);
    var sources =
        new SourceView(directory, project.sources(), project.testSources())
            .withClassSources(new ClassSources(project.classes()))
            .withInvocation(Invocation.of(command.commands(), target));
    return new Prepared(directory, work, project, sources, startedAt, setup);
  }

  /// The project as typed, and its suite when it isn't the default: its tests are the suite's, so
  /// it's part of the project every command offered names.
  private static List<String> withSuite(List<String> target, String suite) {
    if (suite.equals(BuildTool.DEFAULT_SUITE)) {
      return target;
    }
    var named = new ArrayList<>(target);
    named.add("--suite=" + suite);
    return named;
  }

  /// Where mutastic keeps the module's run record, logs and reports, made if it isn't there yet:
  /// with a setup a build plugin wrote, no build has run to make it. A suite but the default keeps
  /// its own, under `suites/`: its run never compares with another suite's.
  private static Path workDirectory(BuildTool tool, Path directory, String module, String suite) {
    var moduleWork = tool.workDirectory(directory, module);
    var work =
        suite.equals(BuildTool.DEFAULT_SUITE)
            ? moduleWork
            : moduleWork.resolve("suites").resolve(suite);
    try {
      Files.createDirectories(work);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return work;
  }

  private static HumanText humanText(Command command, Output output, SourceView sources) {
    return new HumanText(
            sources, command.threshold(), output.style(), detail(output.format()), output.words())
        .eachMutant(command.eachMutant());
  }

  /// What `--experimental` and `MUTASTIC_EXPERIMENTAL` take to turn on ghosts.
  static final String GHOSTS = "ghosts";

  /// What the engine runs with: the command's choices, and the project's `mutastic.yaml`.
  private static Engine.Options options(Command command, Terminal terminal, Prepared prepared) {
    var directory = prepared.directory();
    return new Engine.Options(
            command.only(),
            command.confirmCatches(),
            command.workers().orElse(Engine.defaultWorkers()),
            command.suggestions() && !command.verify(),
            terminal.auditArid(),
            java.util.Set.of(),
            policy(command, prepared),
            command.since().map(since -> new Git(directory).changedSince(since)),
            command.experimental().contains(GHOSTS) || terminal.experimental().contains(GHOSTS))
        .withKillMatrix(command.killMatrix())
        .withShard(command.shard().orElse(Shard.WHOLE));
  }

  /// The project's say, from the module's nearest `mutastic.yaml` and the run's ignores, with the
  /// operators to run: the command's `--operators`, else `operators:`, else the defaults. One
  /// mutant, asked for by id, is looked for among every operator (`all`), so a stronger or extended
  /// one's id is found as any other's.
  private static Policy policy(Command command, Prepared prepared) {
    var directory = prepared.directory();
    var configured = Configuration.nearest(prepared.project().workingDirectory(), directory);
    var configuration = Configuration.read(configured);
    var chosen =
        !command.only().isEmpty()
            ? List.of("all")
            : command.operators().isEmpty() ? configuration.operators() : command.operators();
    var policy =
        configuration
            .policy(configured)
            .withIgnores(Ignores.read(directory).stream().map(Ignores.Entry::ignore).toList());
    policy = policy.withOperators(chosen, policy.mutators());
    try {
      OperatorSet.active(policy);
    } catch (IllegalArgumentException e) {
      var source =
          command.operators().isEmpty() ? "operators: in " + Configuration.FILE : "--operators";
      throw new UsageException(source + ": " + e.getMessage());
    }
    return policy;
  }

  /// What `verify` says of each of its mutants, and whether every one passed.
  private static int verified(List<MutantResult> results, PrintStream out) {
    int exit = PASSED;
    for (MutantResult result : results) {
      exit = Math.max(exit, verified(result, out));
    }
    return exit;
  }

  /// What `verify` says of one of its mutants, and whether it passed: caught, or skipped, which
  /// leaves nothing to catch. A skipped mutant once read "isn't reached by any test", and an agent
  /// went looking for the test that should reach it.
  private static int verified(MutantResult result, PrintStream out) {
    var fate =
        switch (result.status()) {
          case CAUGHT, TIMED_OUT -> "is caught by " + result.caughtBy().orElse("a timeout");
          case MISSED -> "is still missed";
          case NOT_COVERED -> "isn't reached by any test";
          case SKIPPED ->
              "is skipped (%s), so it isn't run and needs no test"
                  .formatted(result.mutant().skipped().orElse("skipped"));
        };
    out.printf("mutastic: %s %s\n", result.mutant().id(), fate);
    return settled(result) ? PASSED : FAILED;
  }

  /// Whether a mutant asked for by id passes: caught, or skipped, with nothing to catch.
  private static boolean settled(MutantResult result) {
    return result.status().detected() || result.status() == Status.SKIPPED;
  }

  /// Prints a finished run's result.
  @FunctionalInterface
  private interface Printer {
    void print(Report report, Optional<Comparison> comparison, List<String> written);
  }

  /// For one format: what hears about mutants as they finish, and what prints the result.
  private record Display(Listener listener, Printer printer) {}

  private static Display display(
      Command command, Prepared prepared, Output output, HumanText human) {
    var oneMutant = !command.only().isEmpty();
    return switch (output.format()) {
      case "jsonl" -> jsonl(command, prepared, output);
      case "agent" -> {
        var agent =
            new AgentText(prepared.sources(), command.threshold(), output.words())
                .eachMutant(command.eachMutant());
        yield new Display(
            output.progress(),
            (report, comparison, written) ->
                output.out().print(agent.render(report, comparison, oneMutant)));
      }
      default ->
          new Display(
              output.progress(),
              (report, comparison, written) ->
                  output
                      .out()
                      .print(human.render(report, comparison, run(command, prepared, written))));
    };
  }

  /// What the summary says of the run beyond its result: when and how long, the reports it wrote,
  /// and the tests it was told to leave out.
  private static HumanText.Run run(Command command, Prepared prepared, List<String> written) {
    return new HumanText.Run(
        prepared.startedAt(),
        prepared.setup(),
        written,
        !command.only().isEmpty(),
        command.excludeTests());
  }

  /// JSON Lines: the run's start now, each mutant as it finishes, and the result at the end.
  private static Display jsonl(Command command, Prepared prepared, Output output) {
    var events =
        new JsonEvents(
            output.out(), prepared.sources(), command.threshold(), shareOf(command, prepared));
    events.runStarted(prepared.project().name());
    if (!command.testsFrom().isEmpty()) {
      // Each mutant's result is known only once every module's tests have run: all at the end.
      return new Display(
          output.progress(),
          (report, comparison, written) -> {
            report.results().forEach(events::mutantFinished);
            events.runFinished(report, comparison, written, !command.only().isEmpty());
          });
    }
    return new Display(
        progressAndEvents(output.progress(), events),
        (report, comparison, written) ->
            events.runFinished(report, comparison, written, !command.only().isEmpty()));
  }

  private static Listener progressAndEvents(Progress progress, JsonEvents events) {
    return new Listener() {
      @Override
      public void coverageFinished(CoveragePass pass) {
        progress.coverageFinished(pass);
      }

      @Override
      public void mutantFinished(MutantResult result) {
        progress.mutantFinished(result);
        events.mutantFinished(result);
      }

      @Override
      public void fileFinished(String topLevelClass, List<MutantResult> results) {
        progress.fileFinished(topLevelClass, results);
      }

      @Override
      public void mutantRevised(MutantResult result) {
        events.mutantRevised(result);
      }
    };
  }

  /// What a full run keeps: its comparison with the last run, and the report files it wrote.
  private record Kept(Optional<Comparison> comparison, List<String> written) {
    static final Kept NOTHING = new Kept(Optional.empty(), List.of());
  }

  /// Compares with the last run under the same operators before keeping this one, then writes the
  /// report files.
  private static Kept keep(
      Command command, Prepared prepared, Report report, List<Path> otherTests) {
    var comparison = recorded(command, prepared, report, otherTests);
    var written = new ArrayList<>(writeReports(command, prepared, report));
    var share = command.shard().map(shard -> shareEvents(prepared, shard));
    share.ifPresent(file -> written.add(shownFrom(prepared.directory(), file)));
    var kept = new Kept(comparison, List.copyOf(written));
    if (share.isPresent()) {
      keepShare(command, prepared, report, kept);
    }
    return kept;
  }

  /// A share's events, kept for `--from-shards`: as `--format=jsonl` writes them, whatever the
  /// format shown.
  private static void keepShare(Command command, Prepared prepared, Report report, Kept kept) {
    Shares.keep(
        shareEvents(prepared, command.shard().orElseThrow()),
        out -> {
          var events =
              new JsonEvents(
                  out, prepared.sources(), command.threshold(), shareOf(command, prepared));
          events.runStarted(prepared.project().name());
          report.results().forEach(events::mutantFinished);
          events.runFinished(report, kept.comparison(), kept.written(), false);
        });
  }

  /// Where a share keeps its events, beside its run record.
  private static Path shareEvents(Prepared prepared, Shard shard) {
    return Shares.folder(prepared.work(), shard).resolve(Shares.EVENTS);
  }

  /// Which share of a split run this is, and what it ran on, when it's one.
  private static Optional<JsonEvents.Share> shareOf(Command command, Prepared prepared) {
    return command
        .shard()
        .map(
            shard ->
                new JsonEvents.Share(
                    shard.number(), shard.of(), version(), RunRecord.codeOf(prepared.project())));
  }

  /// A file as a report's is listed: relative to the project where it's in it.
  private static String shownFrom(Path directory, Path file) {
    return file.startsWith(directory) ? slashed(directory.relativize(file)) : file.toString();
  }

  /// Keeps a run of every mutant as the last run (what `explain` and the next run's comparison
  /// read), with its comparison with the one before.
  private static Optional<Comparison> recorded(
      Command command, Prepared prepared, Report report, List<Path> otherTests) {
    if (report.scope().isPresent()) {
      // A change's score says nothing about the whole module's: no comparison, no record.
      return Optional.empty();
    }
    var records = new RunRecords(recordsOf(prepared, command.shard()));
    var record =
        RunRecord.of(
                prepared.project(),
                otherTests,
                RunRecord.toolOf(List.of(codeSource(Engine.class), workerJar())),
                report)
            .under(OperatorSet.active(policy(command, prepared)));
    // Only an earlier run of the same project, in the same place, under the same operators, says
    // anything about this one.
    var comparison = records.comparableTo(record).map(record::against);
    records.keep(record);
    return comparison;
  }

  /// Where a run's records are kept: a share's apart, compared only with the same share's, so a
  /// whole run is never compared with a part of one.
  private static Path recordsOf(Prepared prepared, Optional<Shard> shard) {
    return shard.map(share -> Shares.folder(prepared.work(), share)).orElse(prepared.work());
  }

  /// The report files asked for, as they're written: relative to the project where they're in it.
  private static List<String> writeReports(Command command, Prepared prepared, Report report) {
    var directory = prepared.directory();
    var files = new ReportFiles(directory, prepared.sources(), command.threshold(), version());
    var written = new ArrayList<String>();
    for (var requested : command.reports()) {
      var file =
          requested
              .path()
              .map(directory::resolve)
              .orElse(prepared.work().resolve(defaultName(requested.kind())));
      files.write(requested.kind(), report, file);
      written.add(shownFrom(directory, file));
    }
    return written;
  }

  private static int exitCode(Command command, Report report) {
    if (!report.failingAlone().isEmpty()) {
      return TESTS_FAIL; // a test fails on the unmutated code, as before any mutant
    }
    // One mutant asked for by id passes when it's caught or skipped, as `verify` does; a whole
    // run, on its score.
    if (!command.only().isEmpty()) {
      return report.results().stream().allMatch(Main::settled) ? PASSED : FAILED;
    }
    return report.passes(command.threshold()) ? PASSED : FAILED;
  }

  /// Where a report goes unless told: beside the run record.
  private static String defaultName(String kind) {
    return switch (kind) {
      case "json" -> "mutation.json";
      case "html" -> "mutation.html";
      case "sarif" -> "mutation.sarif";
      default -> "mutations.xml";
    };
  }

  /// The project as it would be typed from `here`: `samples/pricing :api`, a path relative to
  /// `here` when it's inside it, else absolute; nothing for `here` and its root project.
  static List<String> target(Path here, Path directory, String module) {
    var words = new ArrayList<String>();
    if (!directory.equals(here)) {
      words.add(
          directory.startsWith(here) ? slashed(here.relativize(directory)) : directory.toString());
    }
    if (!module.equals(":")) {
      words.add(module);
    }
    return words;
  }

  /// A bar on a terminal for people; counter lines in logs; nothing for agents and JSONL readers,
  /// who only want the result.
  private static Progress.Mode progressMode(String choice, String format, Terminal terminal) {
    return switch (choice) {
      case "bar" -> Progress.Mode.BAR;
      case "counter" -> Progress.Mode.COUNTER;
      case "none" -> Progress.Mode.NONE;
      default ->
          format.equals("agent") || format.equals("jsonl")
              ? Progress.Mode.NONE
              : terminal.interactive() ? Progress.Mode.BAR : Progress.Mode.COUNTER;
    };
  }

  private static String version() {
    var version = Main.class.getPackage().getImplementationVersion();
    return version == null ? "dev" : version;
  }

  private static Path codeSource(Class<?> type) {
    try {
      return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /// The worker jar ships on mutastic's own classpath; the engine starts it in its own process.
  private static Path workerJar() {
    try {
      var worker = Class.forName("io.mutastic.worker.Worker", false, Main.class.getClassLoader());
      return Path.of(worker.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (ClassNotFoundException | URISyntaxException e) {
      throw new IllegalStateException("mutastic's test worker is missing from its installation", e);
    }
  }

  /// The commands, and whether each works on one mutant. Every one that does must be offered for
  /// each mutant in the output (`MutantCommand`), and its tests check that. Each says how it's
  /// written, what it does and how it's used, for help and the docs.
  enum Subcommand {
    RUN(
        false,
        "run [DIR] [:MODULE] [OPTIONS]",
        "Mutate the code and run its tests against each mutant",
        "DIR is the build, here unless given, and :MODULE one of its modules: as Gradle names it"
            + " (:app), or as Maven's -pl takes it (:services/api). The run passes when the score"
            + " reaches the threshold. A full run writes report files, keeps a record the next run"
            + " compares with, and offers the commands to work on each missed mutant.",
        "0 when the score reaches the threshold, 1 when it doesn't.",
        List.of(
            "mutastic run",
            "mutastic run samples/pricing --threshold=70",
            "mutastic run --since=main",
            "mutastic run . :core --tests-from=dependents")),
    VERIFY(
        true,
        "verify ID... [DIR] [:MODULE]",
        "Run mutants' tests: it passes when they catch every one not skipped",
        "How a fix is proved: each missed mutant offers it as \"done when\", and a card with"
            + " several mutants names them all.",
        "0 when it catches each mutant named, or the mutant is skipped; 1 while one is still missed.",
        List.of(
            "mutastic verify m855c8e3c",
            "mutastic verify m855c8e3c samples/pricing",
            "mutastic verify m855c8e3c m2e491c40")),
    EXPLAIN(
        true,
        "explain ID [DIR] [:MODULE]",
        "Everything known of one mutant, with its tests run",
        "Its card, the change in its bytecode, what its operator does, and its result in the last"
            + " full run.",
        "0 whatever the mutant's result: it explains, it doesn't judge.",
        List.of("mutastic explain m855c8e3c")),
    IGNORE(
        true,
        "ignore ID [DIR] [:MODULE] --reason=WHY",
        "Record a decision not to test one mutant",
        "It goes in .mutastic/ignores.yaml with its line and the reason, to commit and review, and"
            + " lapses when that line changes. --remove takes one back; ignore --prune takes away"
            + " every one that has lapsed. --propose records it in .mutastic/proposals.yaml"
            + " instead, for a person to accept with --accept: how a coding agent says a mutant"
            + " isn't worth a test, since until then it still counts as missed. A proposal that a"
            + " mutant is equivalent or unreachable says the one case where it differs, and why"
            + " that can't happen.",
        "0 when the decision is recorded, proposed, accepted or removed; 1 when --accept names a mutant that isn't proposed.",
        List.of(
            "mutastic ignore m855c8e3c --reason=\"only logs\"",
            "mutastic ignore m855c8e3c --propose --as=unreachable --reason=\"Differs only when"
                + " total is 100. That can't happen because prices end in 9.\"",
            "mutastic ignore --accept m855c8e3c",
            "mutastic ignore --remove m855c8e3c",
            "mutastic ignore --prune")),
    NEXT(
        false,
        "next [DIR] [:MODULE] [OPTIONS]",
        "Show the missed mutant most worth a test first, and why",
        "The missed mutants as a queue, one line's at a time, in the order a run ranks them: its"
            + " card, why it's first, and the command that says when it's done. --top shows the"
            + " first few, spread over the code. With a baseline, new misses come before the"
            + " baseline's. While the code under test is the same, it runs only the mutants the"
            + " last next left uncaught, so any a new test catches drop out. For an agent, one"
            + " task, and to run next again after it.",
        "0 when nothing missed is left; 1 while something is queued.",
        List.of("mutastic next", "mutastic next --top=5", "mutastic next --format=agent")),
    BASELINE(
        false,
        "baseline [DIR] [:MODULE]",
        "Record every mutant missed now, so later misses are told from them",
        "Runs the mutants and writes each one missed or not covered, by id, to"
            + " .mutastic/baseline.yaml, to commit. From then on a miss that isn't in it is new:"
            + " gate holds only new misses, and next offers them first. It's never rewritten to"
            + " take in new misses: --prune runs the mutants and drops the baseline's that are"
            + " caught now.",
        "0 when the baseline is written or pruned; 1 when there's one already and --prune isn't"
            + " given.",
        List.of("mutastic baseline", "mutastic baseline --prune")),
    GATE_OPEN(
        false,
        "gate open [DIR] [:MODULE]",
        "Start gating a coding agent's task: keep where it starts",
        "Keeps the commit the task starts from, its ignores, and how many tests run and are"
            + " disabled, in the module's work directory, so gate check can say whether the task is"
            + " done. With --hook, once for each of the agent's sessions.",
        "0 once it has kept where the task starts; 2 outside a git repository with a commit.",
        List.of("mutastic gate open", "mutastic gate open --hook=claude")),
    GATE_CHECK(
        false,
        "gate check [DIR] [:MODULE]",
        "Say whether a gated task is done: its changed lines' mutants caught",
        "Runs the mutants on the lines changed since gate open. The task is done when each is"
            + " caught or proposed for a person to ignore, no ignore was made by the task, and no"
            + " test was taken away or disabled; until then it says what's left, as the agent"
            + " format does. With --hook it answers as the agent's hook, and lets the agent stop,"
            + " saying what's left, when it changed nothing since it was sent back, or after"
            + " --tries.",
        "0 when the task is done, 1 when it isn't, 2 when no task is open. With --hook, 0 either way: the answer is what it prints, a block or a message, as the agent's hooks read it, and with nothing open the agent may stop.",
        List.of("mutastic gate check", "mutastic gate check --hook=claude")),
    GATE_CLOSE(
        false,
        "gate close [DIR] [:MODULE]",
        "Stop gating a task: forget where it started",
        "The next gate open starts a new task.",
        "0 whether a task was open or not: it says which, or with --hook says nothing.",
        List.of("mutastic gate close"));

    final boolean takesMutantId;
    final String synopsis;
    final String summary;
    final String detail;

    /// How it exits, beyond the statuses every command shares (a usage error, failing tests).
    final String exits;

    // Always a List.of, which can't change: the check goes by the declared type, which can.
    @SuppressWarnings("ImmutableEnumChecker")
    final List<String> examples;

    Subcommand(
        boolean takesMutantId,
        String synopsis,
        String summary,
        String detail,
        String exits,
        List<String> examples) {
      this.takesMutantId = takesMutantId;
      this.synopsis = synopsis;
      this.summary = summary;
      this.detail = detail;
      this.exits = exits;
      this.examples = examples;
    }

    /// The command a command line gives, `mutastic gate check --hook=claude` say: the longest that
    /// matches, so `gate check`, not some shorter one.
    static Optional<Subcommand> in(String commandLine) {
      return java.util.Arrays.stream(values())
          .filter(
              command -> {
                var start = "mutastic " + command.word();
                return commandLine.equals(start) || commandLine.startsWith(start + " ");
              })
          .max(java.util.Comparator.comparingInt(command -> command.word().length()));
    }

    /// As typed: `run`, `gate open`.
    String word() {
      return name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    /// Its page in the docs: `run`, `gate-open`.
    String page() {
      return word().replace(' ', '-');
    }

    boolean gate() {
      return word().startsWith("gate ");
    }

    /// How many of `words`, from the first, name a command: two for `gate open`, one otherwise.
    static int length(List<String> words) {
      return words.size() > 1 && words.getFirst().equals("gate") ? 2 : 1;
    }

    /// The command `words` start with, if any.
    static Optional<Subcommand> at(List<String> words) {
      return words.isEmpty()
          ? Optional.empty()
          : of(String.join(" ", words.subList(0, length(words))));
    }

    static Optional<Subcommand> of(String word) {
      return java.util.Arrays.stream(values())
          .filter(command -> command.word().equals(word))
          .findFirst();
    }
  }

  static final class UsageException extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    UsageException(String message) {
      super(message);
    }
  }

  /// A report file to write: `json`, `html` or `xml`, where the default isn't wanted.
  record RequestedReport(String kind, Optional<Path> path) {}

  /// `json` and `html`, unless `--report` says otherwise.
  static final List<RequestedReport> DEFAULT_REPORTS =
      List.of(
          new RequestedReport("json", Optional.empty()),
          new RequestedReport("html", Optional.empty()));

  /// How the result is shown, as the command line chose.
  ///
  /// @param format empty for the default, which depends on where output goes
  /// @param colour `auto`, `always` or `never`, as are `links` and `progress`
  /// @param eachMutant a card for every missed mutant, not one for the mutants of each line
  record Shown(
      Optional<String> format,
      String colour,
      String links,
      String progress,
      Optional<String> vocabulary,
      boolean eachMutant) {}

  /// A command line, read.
  ///
  /// @param workers empty for the default, which depends on the machine
  /// @param reports the report files to write after a full run
  /// @param experimental what's experimental that the run turns on: `ghosts`
  record Command(
      Subcommand subcommand,
      Path directory,
      String module,
      Shown shown,
      int threshold,
      List<String> only,
      Catches catches,
      Optional<Integer> workers,
      boolean suggestions,
      List<RequestedReport> reports,
      List<String> testsFrom,
      List<String> operators,
      List<String> excludeTests,
      String suite,
      Optional<String> since,
      Optional<Ignoring> ignore,
      Optional<Path> setup,
      String commands,
      Gating gating,
      java.util.Set<String> experimental,
      int top,
      Sharing sharing) {
    Optional<String> format() {
      return shown.format();
    }

    /// With `--shard`, the share of the mutants this run runs.
    Optional<Shard> shard() {
      return sharing.shard();
    }

    /// With `--from-shards`, the directory of the shares this run is made of.
    Optional<Path> fromShards() {
      return sharing.from();
    }

    boolean confirmCatches() {
      return catches.confirm();
    }

    boolean killMatrix() {
      return catches.every();
    }

    Optional<String> vocabulary() {
      return shown.vocabulary();
    }

    String colour() {
      return shown.colour();
    }

    String links() {
      return shown.links();
    }

    String progress() {
      return shown.progress();
    }

    boolean eachMutant() {
      return shown.eachMutant();
    }

    boolean verify() {
      return subcommand == Subcommand.VERIFY;
    }

    boolean explain() {
      return subcommand == Subcommand.EXPLAIN;
    }

    /// The same command, for `ignore`.
    Command ignoring(Ignoring how) {
      return new Command(
          subcommand,
          directory,
          module,
          shown,
          threshold,
          only,
          catches,
          workers,
          suggestions,
          reports,
          testsFrom,
          operators,
          excludeTests,
          suite,
          since,
          Optional.of(how),
          setup,
          commands,
          gating,
          experimental,
          top,
          sharing);
    }

    /// The run `gate check` makes: this command's project and operators, on the lines changed since
    /// `base`, every mutant reported, no report files.
    Command runSince(String base) {
      return new Command(
          Subcommand.RUN,
          directory,
          module,
          shown,
          0,
          List.of(),
          catches,
          workers,
          suggestions,
          List.of(),
          testsFrom,
          operators,
          excludeTests,
          suite,
          Optional.of(base),
          Optional.empty(),
          setup,
          commands,
          gating,
          experimental,
          top,
          Sharing.NONE);
    }

    static Command parse(String[] args) {
      var words = List.of(args);
      if (words.isEmpty() || Subcommand.at(words).isEmpty()) {
        throw new UsageException(
            words.isEmpty()
                ? "no command"
                : words.getFirst().equals("gate")
                    ? "gate needs what to do: open, check or close"
                    : "unknown command " + words.getFirst());
      }
      var subcommand = Subcommand.at(words).orElseThrow();
      var parser = new Parser();
      var rest = new java.util.ArrayDeque<>(words.subList(Subcommand.length(words), args.length));
      while (!rest.isEmpty()) {
        parser.read(rest.removeFirst(), rest);
      }
      return parser.command(subcommand);
    }

    /// The arguments after the subcommand, read one at a time.
    private static final class Parser {
      private static final java.util.regex.Pattern MUTANT_ID =
          java.util.regex.Pattern.compile("m[0-9a-f]{8}");

      private final List<String> positional = new ArrayList<>();
      private Optional<String> format = Optional.empty();
      private int threshold = DEFAULT_THRESHOLD;
      private List<String> only = List.of();
      private boolean confirmCatches = false;
      private boolean eachMutant = false;
      private Optional<Integer> workers = Optional.empty();
      private String colour = "auto";
      private String links = "auto";
      private String progress = "auto";
      private boolean suggestions = true;
      private @Nullable List<RequestedReport> reports = null;
      private Optional<String> vocabulary = Optional.empty();
      private final List<String> testsFrom = new ArrayList<>();
      private final List<String> operators = new ArrayList<>();
      private final java.util.Set<String> experimental = new java.util.LinkedHashSet<>();
      private final List<String> excludeTests = new ArrayList<>();
      private String suite = BuildTool.DEFAULT_SUITE;
      private Optional<String> since = Optional.empty();
      private String reason = "";
      private String kind = "";
      private boolean prune = false;
      private boolean remove = false;
      private boolean propose = false;
      private String hook = "";
      private int tries = 3;
      private int top = 1;
      private boolean killMatrix = false;
      private Optional<Shard> shard = Optional.empty();
      private Optional<Path> fromShards = Optional.empty();
      private boolean accept = false;
      private Optional<Path> setup = Optional.empty();
      private String commands = "mutastic";

      /// Whether the argument before was `--since` without a value, which it may go without: the
      /// word after it is then the directory, so one that isn't a directory was its value.
      private boolean afterBareSince = false;

      void read(String arg, java.util.Deque<String> rest) {
        boolean sinceBefore = afterBareSince;
        afterBareSince = false;
        // The build plugins' form of an option, `-Dmutastic.only=ID`: how they pass their settings,
        // and how the commands they offer write them, so each parses back to the command it
        // stands for.
        var properties = Options.fromProperty(arg);
        if (properties.isPresent()) {
          properties.get().forEach(each -> read(each, new java.util.ArrayDeque<>()));
        } else if (arg.startsWith("--")) {
          int equals = arg.indexOf('=');
          var option = Options.named(equals < 0 ? arg : arg.substring(0, equals));
          set(option, value(option, arg, equals, rest));
        } else {
          positional(arg, sinceBefore);
        }
      }

      /// The value after `=`, or the next argument for an option that always takes one; null for a
      /// flag, and for `--since` without one.
      private static @Nullable String value(
          Option option, String arg, int equals, java.util.Deque<String> rest) {
        if (equals >= 0) {
          if (option.kind() == Option.Kind.FLAG) {
            throw new UsageException(option.name() + " takes no value");
          }
          return arg.substring(equals + 1);
        }
        return switch (option.kind()) {
          case FLAG, OPTIONAL_VALUE -> null;
          case VALUE, REPEATED -> {
            if (rest.isEmpty()) {
              throw new UsageException(
                  "%s needs a value: %s".formatted(option.name(), option.synopsis()));
            }
            yield rest.removeFirst();
          }
        };
      }

      private void set(Option option, @Nullable String value) {
        var given = value == null ? "" : value;
        switch (option.name()) {
          case "--threshold" -> threshold = number(option, given, 0, 100);
          case "--since" -> since(value);
          case "--only" -> only = onlyIds(given);
          case "--tests-from" -> testsFrom.add(module(given));
          case "--operators" -> operators(given);
          case "--experimental" -> experimental.add(choice(option, given));
          case "--exclude-tests" -> excludeTests(given);
          case "--suite" -> suite = suite(given);
          case "--workers" -> workers = Optional.of(number(option, given, 1, Integer.MAX_VALUE));
          case "--confirm-catches" -> confirmCatches = true;
          case "--each-mutant" -> eachMutant = true;
          case "--no-suggestions" -> suggestions = false;
          case "--report" -> report(given);
          case "--format" -> format = Optional.of(choice(option, given));
          case "--vocabulary" -> vocabulary = Optional.of(choice(option, given));
          case "--color" -> colour = choice(option, given);
          case "--hyperlinks" -> links = choice(option, given);
          case "--progress" -> progress = choice(option, given);
          default -> setIgnoringOrForAPlugin(option, given);
        }
      }

      /// `--only=ID[,ID…]`'s ids: an empty one, from `--only=` or a comma too many, is no id.
      private static List<String> onlyIds(String given) {
        var ids = List.of(given.split(",", -1));
        if (given.isBlank()) {
          throw new UsageException("--only needs a mutant's id: --only=ID, as a card shows it");
        }
        if (ids.stream().anyMatch(String::isBlank)) {
          throw new UsageException(
              "--only=%s has an empty id between its commas: --only=ID,ID".formatted(given));
        }
        return ids;
      }

      /// `--since=REF`, or a bare `--since`, whose ref may be the word after it.
      private void since(@Nullable String value) {
        since = Optional.of(value == null ? "" : value);
        afterBareSince = value == null;
      }

      /// `ignore`'s own options, and those the build plugins pass.
      private void setIgnoringOrForAPlugin(Option option, String given) {
        switch (option.name()) {
          case "--reason" -> reason = given.strip();
          case "--as" -> kind = choice(option, given);
          case "--remove" -> remove = true;
          case "--propose" -> propose = true;
          case "--accept" -> accept = true;
          case "--prune" -> prune = true;
          case "--hook" -> {
            hook = choice(option, given);
            progress = "none"; // a hook's stderr is the agent's to read, not progress
          }
          case "--tries" -> tries = number(option, given, 1, 100);
          case "--top" -> top = number(option, given, 1, io.mutastic.report.Queue.MOST);
          case "--kill-matrix" -> killMatrix = true;
          case "--shard" -> shard = Optional.of(shard(option, given));
          case "--from-shards" -> fromShards = Optional.of(Path.of(given));
          case "--setup" -> setup = Optional.of(Path.of(given));
          case "--commands" -> commands = commands(given);
          default -> throw new IllegalStateException("no reader for " + option.name());
        }
      }

      /// `--suite=integrationTest`: a name the build tool checks.
      private static String suite(String value) {
        if (value.isBlank() || value.contains("/") || value.startsWith("-")) {
          throw new UsageException(
              "--suite takes a test suite's name, as integrationTest; not " + value);
        }
        return value.strip();
      }

      /// `--exclude-tests=RacyTest,com.example.PortTest`: test classes by name or full name.
      private void excludeTests(String value) {
        for (String name : value.split(",", -1)) {
          if (!name.isBlank()) {
            excludeTests.add(name.strip());
          }
        }
      }

      /// `--operators=stronger,-stream`: each word, checked when the project's operators are known.
      private void operators(String value) {
        for (String word : value.split(",", -1)) {
          if (!word.isBlank()) {
            operators.add(word.strip());
          }
        }
      }

      /// `--report=kind[:path]`.
      private void report(String value) {
        int colon = value.indexOf(':');
        var kind = colon < 0 ? value : value.substring(0, colon);
        if (!kind.equals("none") && !ReportFiles.KINDS.contains(kind)) {
          throw new UsageException(
              "unknown report %s (one of json, html, xml, none)".formatted(kind));
        }
        if (reports == null) {
          reports = new ArrayList<>(); // the first --report replaces the defaults
        }
        if (!kind.equals("none")) {
          reports.add(
              new RequestedReport(
                  kind,
                  colon < 0 ? Optional.empty() : Optional.of(Path.of(value.substring(colon + 1)))));
        }
      }

      /// `2/4`: the second of four shares, each from 1 to how many there are.
      private static Shard shard(Option option, String value) {
        var parts = value.split("/", -1);
        if (parts.length != 2) {
          throw new UsageException(
              "%s needs a share and how many, as 2/4, not %s".formatted(option.name(), value));
        }
        int of = number(option, parts[1], 1, Shard.MOST);
        int number = number(option, parts[0], 1, of);
        return new Shard(number, of);
      }

      private static String commands(String value) {
        try {
          Invocation.of(value, List.of());
          return value;
        } catch (IllegalArgumentException e) {
          throw new UsageException(String.valueOf(e.getMessage()));
        }
      }

      /// A directory or `:module`. After `--since` without a value, a word that isn't a directory
      /// was meant as its value, which needs `=`: `--since main` reads as the default branch and a
      /// directory called main.
      private void positional(String arg, boolean sinceBefore) {
        if (arg.startsWith("-") && arg.length() > 1) {
          throw new UsageException("unknown option " + arg);
        }
        if (sinceBefore && !arg.startsWith(":") && !Files.isDirectory(Path.of(arg))) {
          throw new UsageException(
              "there's no directory %s: for the changes since %s, write --since=%s"
                  .formatted(arg, arg, arg));
        }
        positional.add(arg);
      }

      Command command(Subcommand subcommand) {
        if (subcommand == Subcommand.IGNORE) {
          return ignoring();
        }
        if (subcommand == Subcommand.BASELINE) {
          // `baseline --prune`: an ignore's prune, of the baseline's mutants the tests catch now.
          return built(subcommand).ignoring(new Ignoring(prune, false, "", "", false, false));
        }
        if (subcommand == Subcommand.VERIFY) {
          only = mutantIds();
        } else if (subcommand.takesMutantId) {
          only = List.of(mutantId(subcommand.word()));
        }
        return built(subcommand);
      }

      /// `ignore ID --reason=…`, or `ignore --prune`.
      private Command ignoring() {
        if (!prune) {
          only = List.of(mutantId("ignore"));
          if (!remove && !accept && reason.isBlank()) {
            throw new UsageException(
                "ignore needs a reason, which every ignore says: --reason=\"…\"");
          }
        }
        if (propose && (accept || remove || prune)) {
          throw new UsageException(
              "--propose records a proposal: not with --accept, --remove or --prune");
        }
        if (propose) {
          checkArgued();
        }
        return built(Subcommand.IGNORE)
            .ignoring(new Ignoring(prune, remove, reason, kind, propose, accept));
      }

      /// A proposal's reason, which a person checks in place of a test: filled in, and for a mutant
      /// said to be equivalent or unreachable, saying why its case can't happen.
      private void checkArgued() {
        if (MutantCommand.Reason.unfilled(this.reason)) {
          throw new UsageException(
              "the reason still has the template's <case> or <why>: fill them in, as"
                  + " --reason=\"Differs only when count is negative. That can't happen because"
                  + " count is a size.\"");
        }
        if (MutantCommand.Reason.ARGUED.contains(kind)
            && !MutantCommand.Reason.argued(this.reason)) {
          throw new UsageException(
              ("a proposal that a mutant is %s needs a reason a person can check: the one case"
                      + " where it differs, and why that can't happen, with a because:"
                      + " --reason=\"%s\"")
                  .formatted(kind, MutantCommand.Reason.TEMPLATE));
        }
      }

      /// `verify`'s ids: every word before the directory that is one, `m` and eight hex digits, or
      /// several split by commas, as a card's `done when` writes them.
      private List<String> mutantIds() {
        var ids = new ArrayList<>(List.of(mutantId("verify").split(",", -1)));
        while (!positional.isEmpty() && MUTANT_ID.matcher(positional.getFirst()).matches()) {
          ids.add(positional.removeFirst());
        }
        return ids;
      }

      /// The id first, or several split by commas: taking a directory for one would read the
      /// current directory as the project, and say it isn't a build.
      private String mutantId(String subcommand) {
        if (positional.isEmpty()) {
          throw new UsageException(needsAnId(subcommand, "<directory>"));
        }
        var given = positional.getFirst();
        for (String id : given.split(",", -1)) {
          if (!MUTANT_ID.matcher(id).matches()) {
            throw new UsageException(notAnId(subcommand, given));
          }
        }
        return positional.removeFirst();
      }

      /// `given` where the id goes: a directory, a module or something else.
      private static String notAnId(String subcommand, String given) {
        if (given.startsWith(":")) {
          return "%s is a module, not a mutant id. %s"
              .formatted(given, needsAnId(subcommand, "<directory> " + given));
        }
        if (Files.isDirectory(Path.of(given))) {
          return "%s is a directory, not a mutant id. %s"
              .formatted(given, needsAnId(subcommand, given));
        }
        return "%s isn't a mutant id. %s".formatted(given, needsAnId(subcommand, "<directory>"));
      }

      /// What `subcommand` needs first, as a run names a mutant, with `after` after it.
      private static String needsAnId(String subcommand, String after) {
        return ("%s needs a mutant id first, an m and eight hex digits as a run prints them:\n"
                + "  mutastic %s m855c8e3c %s")
            .formatted(subcommand, subcommand, after);
      }

      /// What's left once the ids are taken is a directory and a `:module`, each at most once: any
      /// other word was meant for something, and doing the command without it would hide that.
      private void noStrayWords(Subcommand subcommand) {
        var stray = new ArrayList<>(positional);
        stray.stream().filter(arg -> !arg.startsWith(":")).findFirst().ifPresent(stray::remove);
        stray.stream().filter(arg -> arg.startsWith(":")).findFirst().ifPresent(stray::remove);
        if (!stray.isEmpty()) {
          throw new UsageException(
              "%s doesn't take %s: mutastic %s"
                  .formatted(subcommand.word(), String.join(" ", stray), subcommand.synopsis));
        }
      }

      /// The command, once nothing is left over.
      private Command built(Subcommand subcommand) {
        noStrayWords(subcommand);
        return assembled(subcommand, directory(), module());
      }

      private Command assembled(Subcommand subcommand, Path directory, String module) {
        return new Command(
            subcommand,
            directory,
            module,
            new Shown(format, colour, links, progress, vocabulary, eachMutant),
            threshold,
            only,
            new Catches(confirmCatches, killMatrix),
            workers,
            suggestions,
            reports == null ? DEFAULT_REPORTS : List.copyOf(reports),
            List.copyOf(testsFrom),
            List.copyOf(operators),
            List.copyOf(excludeTests),
            suite,
            since,
            Optional.empty(),
            setup,
            commands,
            new Gating(hook, tries),
            java.util.Set.copyOf(experimental),
            top,
            sharing(subcommand));
      }

      /// A run is a share or made of them, not both; made of them, it runs no mutants itself, so
      /// the options for what runs are the shares'.
      private Sharing sharing(Subcommand subcommand) {
        if (fromShards.isPresent() && subcommand != Subcommand.RUN) {
          throw new UsageException("--from-shards is run's: mutastic run --from-shards=DIR");
        }
        var clash = fromShards.isPresent() ? clashWithShares() : Optional.<String>empty();
        if (clash.isPresent()) {
          throw new UsageException(
              ("--from-shards puts the shares' results together as the whole run, and runs no"
                      + " mutant itself: not with %s, which the shares run with")
                  .formatted(clash.get()));
        }
        return new Sharing(shard, fromShards);
      }

      /// The first thing given that's for running mutants, which a run made of shares doesn't do.
      private Optional<String> clashWithShares() {
        var given = new java.util.LinkedHashMap<String, Boolean>();
        given.put("--shard", shard.isPresent());
        given.put("a mutant asked for by id", !only.isEmpty());
        given.put("--since", since.isPresent());
        given.put("--tests-from", !testsFrom.isEmpty());
        return given.entrySet().stream()
            .filter(java.util.Map.Entry::getValue)
            .map(java.util.Map.Entry::getKey)
            .findFirst();
      }

      /// `:app`: another module, named as the module run is; or `dependents`, every module that
      /// depends on this one.
      private static String module(String value) {
        if (!value.equals(DEPENDENTS) && (!value.startsWith(":") || value.length() < 2)) {
          throw new UsageException(
              "--tests-from takes a module, as :app, or dependents; not " + value);
        }
        return value;
      }

      private String module() {
        return positional.stream().filter(arg -> arg.startsWith(":")).findFirst().orElse(":");
      }

      private Path directory() {
        return positional.stream()
            .filter(arg -> !arg.startsWith(":"))
            .findFirst()
            .map(Path::of)
            .orElse(Path.of("."));
      }
    }

    /// `value` as a whole number from `min` to `max`.
    private static int number(Option option, String value, int min, int max) {
      int number;
      try {
        number = Integer.parseInt(value);
      } catch (NumberFormatException e) {
        throw new UsageException("%s needs a whole number, not %s".formatted(option.name(), value));
      }
      if (number < min || number > max) {
        throw new UsageException(
            max == Integer.MAX_VALUE
                ? "%s must be at least %d".formatted(option.name(), min)
                : "%s is from %d to %d, not %d".formatted(option.name(), min, max, number));
      }
      return number;
    }

    /// `value`, if it's one of the option's choices.
    private static String choice(Option option, String value) {
      if (!option.choices().contains(value)) {
        throw new UsageException(
            "unknown %s %s (one of %s)"
                .formatted(option.name().substring(2), value, String.join(", ", option.choices())));
      }
      return value;
    }
  }
}
