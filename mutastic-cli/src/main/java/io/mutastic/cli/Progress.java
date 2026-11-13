package io.mutastic.cli;

import io.mutastic.api.BuildTool;
import io.mutastic.api.CoveragePass;
import io.mutastic.api.Listener;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantCost;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Status;
import io.mutastic.report.LiveEvents;
import java.io.PrintStream;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/// What's happening, on stderr, so stdout stays the result: a line as each phase ends, and while it
/// runs, a live block redrawn in place on a terminal, or a heartbeat line elsewhere once the last
/// line is [#HEARTBEAT] old. `PROGRESS.md` is the contract. A ticker, not the run's events, moves
/// the clock, so it moves while nothing finishes; one lock is around every write.
final class Progress implements Listener, AutoCloseable {
  enum Mode {
    BAR,
    COUNTER,
    NONE
  }

  /// The longest a log goes without a line, unless `MUTASTIC_HEARTBEAT` says otherwise.
  static final Duration HEARTBEAT = Duration.ofSeconds(30);

  /// How often the live block is redrawn, and a heartbeat's age checked.
  static final Duration TICK = Duration.ofMillis(100);

  /// The terminal's width when nothing says it.
  static final int COLUMNS = 100;

  /// How progress looks and keeps time.
  ///
  /// @param colour the live block's spinner green, its phase bold and its bar and fates coloured
  /// @param heartbeat how old the last line may get, off a terminal, before a heartbeat
  /// @param nanos the clock: [System#nanoTime] but in tests
  /// @param ticking whether a ticker redraws and beats, and keys and signals are heard; tests call
  ///   [#tick] and [#ask] themselves
  /// @param columns the terminal's width, which every line of the live block fits
  /// @param tab whether the terminal draws progress in its tab (OSC 9;4), where the block is drawn
  record Settings(
      boolean colour,
      Duration heartbeat,
      LongSupplier nanos,
      boolean ticking,
      int columns,
      boolean tab) {
    /// From `MUTASTIC_HEARTBEAT`, in seconds, `MUTASTIC_COLUMNS` or `COLUMNS`, and what the
    /// terminal says of itself.
    static Settings of(Map<String, String> env, boolean colour) {
      return new Settings(
          colour, heartbeat(env), System::nanoTime, true, columns(env), ProgressChoice.tab(env));
    }

    private static Duration heartbeat(Map<String, String> env) {
      var given = env.getOrDefault("MUTASTIC_HEARTBEAT", "");
      if (given.isBlank()) {
        return HEARTBEAT;
      }
      try {
        double seconds = Double.parseDouble(given);
        if (seconds > 0) {
          return Duration.ofMillis((long) (seconds * 1000));
        }
      } catch (NumberFormatException e) {
        // said below
      }
      throw new Main.ConfigException(
          "environment", "MUTASTIC_HEARTBEAT is a number of seconds above 0, not " + given);
    }

    /// The width the launcher passes in `MUTASTIC_COLUMNS`, as Java can't ask the terminal; else
    /// the shell's `COLUMNS`; else [#COLUMNS].
    static int columns(Map<String, String> env) {
      for (var name : List.of("MUTASTIC_COLUMNS", "COLUMNS")) {
        try {
          int columns = Integer.parseInt(env.getOrDefault(name, "").strip());
          if (columns > 0) {
            return columns;
          }
        } catch (NumberFormatException e) {
          // not a width: the next may say
        }
      }
      return COLUMNS;
    }
  }

  private final Object lock = new Object();
  private final Mode mode;
  private final Settings settings;
  private final ProgressStream stream;

  /// Prints each source file's line as it finishes, for people; none for agents and JSONL.
  private @Nullable BiFunction<List<MutantResult>, Duration, String> fileLines;

  /// When each source file's first mutant started, by its top-level class, for its line's time.
  private final Map<String, Long> fileStarted = new HashMap<>();

  /// Names a mutant as a worker's line shows it.
  private Function<Mutant, String> naming = Mutant::id;

  private @Nullable Ticker ticker;
  private @Nullable Keys keys;

  /// The run's progress in the terminal's tab, where it draws it.
  private final Tab tab = new Tab();

  /// Forgets what a way out must undo, once progress has undone it itself.
  private @Nullable Runnable forgetTab;

  /// Where each phase's start and end and its progress go, as they happen: stdout with
  /// `--format=jsonl`, and the Gradle plugin's file.
  private final List<EventSink> sinks = new java.util.ArrayList<>();

  /// Set when someone asks what's running: `t`, Ctrl-T or SIGUSR1. The ticker takes it.
  private final AtomicBoolean asked = new AtomicBoolean();

  private @Nullable Phase phase;
  private long phaseStarted;
  private String doing = "";
  private @Nullable LogTail log;
  private int done;
  private int total;
  private int frame;
  private final Map<Status, Integer> counts = new EnumMap<>(Status.class);
  private final Workload workload = new Workload();

  /// Each mutant's latest result, by id: a revised one replaces its first.
  private final Map<String, MutantResult> results = new HashMap<>();

  Progress(PrintStream err, Mode mode, Settings settings) {
    this.mode = mode;
    this.settings = settings;
    this.stream = new ProgressStream(err, settings.nanos(), settings.heartbeat(), mode == Mode.BAR);
  }

  /// From now on, each source file's line as it finishes: once the sources are known.
  void showFiles(BiFunction<List<MutantResult>, Duration, String> lines) {
    this.fileLines = lines;
  }

  /// From now on, each phase's start and end to `live`, and how far it is on the heartbeat's
  /// cadence, whatever stderr shows: before the run starts, so its first phase is there.
  void sendEvents(LiveEvents live) {
    sendEvents(live, settings.heartbeat());
  }

  /// The same, with a `progress` event `every` so often.
  void sendEvents(LiveEvents live, Duration every) {
    synchronized (lock) {
      sinks.add(new EventSink(live, every, now()));
    }
  }

  /// From now on, a mutant a worker runs named as `names` says: once the sources are known.
  void nameMutants(Function<Mutant, String> names) {
    this.naming = names;
  }

  /// The first line, before anything slow; and the ticker starts, and `t`, Ctrl-T and SIGUSR1 are
  /// listened for.
  void started(String version, String buildTool) {
    synchronized (lock) {
      say("mutastic %s · reading the %s build…\n".formatted(version, buildTool));
      enter(Phase.READING, "the " + buildTool + " build");
      if ((mode != Mode.NONE || !sinks.isEmpty()) && settings.ticking() && ticker == null) {
        listen();
        if (tabbed()) {
          forgetTab = WaysOut.add(() -> stream.leaving(tab.cleared()), () -> {});
        }
        ticker = Ticker.every(TICK, this::tick);
      }
    }
  }

  /// `t` on a terminal that's also stdin, Ctrl-T (SIGINFO, on macOS and the BSDs) and SIGUSR1.
  private void listen() {
    if (mode == Mode.BAR) {
      keys = Keys.listen(this::ask).orElse(null);
    }
    Signals.on("INFO", this::ask);
    Signals.on("USR1", this::ask);
  }

  /// Someone asks what's running: it's said at the next tick.
  void ask() {
    asked.set(true);
  }

  /// The build tool is building what the run needs, and saying so in its log.
  void building(Optional<BuildTool.Building> building) {
    synchronized (lock) {
      enter(Phase.BUILDING, building.map(BuildTool.Building::task).orElse("reading the build"));
      log = building.map(BuildTool.Building::log).map(LogTail::new).orElse(null);
    }
  }

  /// The build is read and built; the engine is finding the mutants in it.
  void findingMutants() {
    synchronized (lock) {
      if (phase != Phase.BUILDING) {
        enter(Phase.BUILDING, "");
      }
      doing = "finding mutants";
      log = null;
    }
  }

  @Override
  public void mutantsFound(int mutants) {
    synchronized (lock) {
      var built = phase == null ? "" : Frames.built(mutants, elapsed());
      enter(Phase.COVERAGE, "");
      workload.reset(1); // the coverage pass runs on one worker
      if (!built.isEmpty()) {
        say(built);
      }
    }
  }

  @Override
  public void coverageProgress(int ran, int planned) {
    synchronized (lock) {
      done = ran;
      total = planned;
    }
  }

  @Override
  public void testStarted(String test) {
    synchronized (lock) {
      workload.started(0, Workload.Running.test(test, now()));
    }
  }

  /// `1 test`, `3 tests`.
  private static String count(int number, String noun) {
    return number + " " + noun + (number == 1 ? "" : "s");
  }

  @Override
  public void coverageFinished(CoveragePass pass) {
    synchronized (lock) {
      enter(Phase.MUTANTS, "");
      workload.reset(0);
      total = pass.mutants(); // a pass over another module's tests counts its own
      counts.clear();
      results.clear();
      say(coverageLine(pass));
    }
  }

  private static String coverageLine(CoveragePass pass) {
    if (pass.tests() == 0) {
      return "coverage  no tests found · %s\n".formatted(count(pass.mutants(), "mutant"));
    }
    return "coverage  %s on JDK %d in %s%s · %s\n"
        .formatted(
            count(pass.tests(), "test"),
            pass.jdk(),
            io.mutastic.report.HumanText.seconds(pass.took()),
            pass.flaky().isEmpty() ? "" : " (%d flaky, left out)".formatted(pass.flaky().size()),
            count(pass.mutants(), "mutant"));
  }

  @Override
  public void mutantsPlanned(int workers, Duration expected) {
    synchronized (lock) {
      workload.planned(workers, expected);
    }
  }

  @Override
  public void mutantStarted(int worker, Mutant mutant, MutantCost cost) {
    var named = naming.apply(mutant);
    synchronized (lock) {
      fileStarted.putIfAbsent(topLevel(mutant.binaryName()), now());
      workload.started(worker, new Workload.Running(mutant.id(), named, now(), cost));
    }
  }

  @Override
  public void workerIdle(int worker) {
    synchronized (lock) {
      workload.idle(worker);
    }
  }

  @Override
  public void mutantFinished(MutantResult result) {
    synchronized (lock) {
      done++;
      counts.merge(result.status(), 1, Integer::sum);
      results.put(result.mutant().id(), result);
      workload.finished(result.mutant().id());
    }
  }

  /// A source file's line, and its misses under it, as soon as its last mutant is done.
  @Override
  public void fileFinished(String topLevelClass, List<MutantResult> results) {
    var lines = fileLines;
    if (lines != null) {
      synchronized (lock) {
        var started = fileStarted.get(topLevelClass);
        var took =
            started == null
                ? results.stream().map(MutantResult::took).reduce(Duration.ZERO, Duration::plus)
                : Duration.ofNanos(now() - started);
        say(lines.apply(results, took));
      }
    }
  }

  /// `com.example.Pricing` for `com.example.Pricing$Rates`: the class its source file is named for.
  private static String topLevel(String binaryName) {
    int dollar = binaryName.indexOf('$', binaryName.lastIndexOf('.') + 1);
    return dollar < 0 ? binaryName : binaryName.substring(0, dollar);
  }

  @Override
  public void mutantRevised(MutantResult result) {
    synchronized (lock) {
      var first = results.put(result.mutant().id(), result);
      if (first != null) {
        counts.merge(first.status(), -1, Integer::sum);
      }
      counts.merge(result.status(), 1, Integer::sum);
    }
  }

  /// Every mutant has its result; what comes after them is under way.
  @Override
  public void checking(String step, int stepDone, int stepTotal) {
    synchronized (lock) {
      if (phase != Phase.CHECKING) {
        enter(Phase.CHECKING, step);
      }
      doing = step;
      done = stepDone;
      total = stepTotal;
    }
  }

  /// Something the run decided that the person should know, on a line of its own.
  void note(String text) {
    synchronized (lock) {
      say(text + "\n");
    }
  }

  /// Another module's tests are about to run over the mutants still uncaught.
  void testsFrom(String module) {
    synchronized (lock) {
      say("tests from %s · reading its build…\n".formatted(module));
      enter(Phase.READING, "the build of " + module);
    }
  }

  /// The run's work is done: the last phase's line, and the live block gone, so the result starts
  /// on a clean line.
  void finished() {
    synchronized (lock) {
      leave();
      stream.clear(tab.cleared());
    }
  }

  /// Progress stops: the live block goes, nothing more is said, and the terminal's input is as it
  /// was.
  @Override
  public void close() {
    var running = ticker;
    if (running != null) {
      running.close();
      ticker = null;
    }
    var listening = keys;
    if (listening != null) {
      listening.close();
      keys = null;
    }
    synchronized (lock) {
      phase = null;
      stream.clear(tab.cleared());
    }
    var forget = forgetTab;
    if (forget != null) {
      forget.run();
      forgetTab = null;
    }
  }

  /// What the ticker does: what's running, if asked; then the live block redrawn, or a heartbeat if
  /// one is due.
  void tick() {
    synchronized (lock) {
      if (phase == null) {
        return;
      }
      if (asked.getAndSet(false)) {
        say(String.join("\n", Frames.dump(snapshot(), colour())) + "\n");
      }
      if (mode == Mode.BAR) {
        stream.draw(tabNow(), block());
        frame++;
      } else if (mode == Mode.COUNTER && stream.heartbeatDue()) {
        stream.line(Frames.heartbeat(snapshot()), "", List.of());
      }
      sendProgress();
    }
  }

  /// A `progress` event to each sink whose last is as old as its cadence.
  private void sendProgress() {
    var current = phase;
    if (current == null) {
      return;
    }
    long at = now();
    for (var sink : sinks) {
      if (sink.due(at)) {
        sink.events().progress(current.word(), ProgressFields.of(snapshot()));
      }
    }
  }

  /// A permanent line, with the live block drawn again under it, in one write.
  private void say(String text) {
    if (mode != Mode.NONE) {
      stream.line(text, tabNow(), block());
    }
  }

  /// From now on, the tab turns red when the score falls below `threshold`: not with
  /// `--fail-on-new`, where the score doesn't decide.
  void redBelow(int threshold) {
    synchronized (lock) {
      tab.redBelow(threshold);
    }
  }

  /// Whether the tab shows the run's progress: on a terminal that says it draws it.
  private boolean tabbed() {
    return mode == Mode.BAR && settings.tab();
  }

  /// What the tab shows now, while a phase runs, where it's drawn.
  private String tabNow() {
    return tabbed() && phase != null ? tab.now(snapshot()) : "";
  }

  /// The live block, on a terminal, while a phase runs.
  private List<String> block() {
    return mode == Mode.BAR && phase != null
        ? Frames.live(snapshot(), frame, settings.columns(), settings.colour())
        : List.of();
  }

  /// A new phase, its clock at 0 and its workers idle, once the last has said how it ended.
  private void enter(Phase next, String what) {
    leave();
    phase = next;
    phaseStarted = now();
    sinks.forEach(sink -> sink.events().phaseStarted(next.word()));
    doing = what;
    log = null;
    done = 0;
    total = 0;
    workload.idle();
  }

  /// The line a phase ends with, where it isn't said as the next starts: every mutant's first
  /// result, and what was checked after them.
  private void leave() {
    int missed = counts.getOrDefault(Status.MISSED, 0);
    var ended =
        switch (phase) {
          case MUTANTS -> Frames.ran(snapshot(), elapsed(), colour());
          case CHECKING -> missed > 0 ? Frames.checked(missed, suggested(), elapsed()) : "";
          case null, default -> "";
        };
    var ending = phase;
    if (ending != null) {
      var took = elapsed();
      sinks.forEach(sink -> sink.events().phaseFinished(ending.word(), took));
    }
    phase = null;
    if (!ended.isEmpty()) {
      say(ended);
    }
  }

  /// The missed mutants a test was found for.
  private int suggested() {
    return (int)
        results.values().stream()
            .filter(result -> result.status() == Status.MISSED)
            .filter(result -> result.suggestion().isPresent())
            .count();
  }

  /// Colour only where the block is drawn in place: a log's lines are plain.
  private boolean colour() {
    return mode == Mode.BAR && settings.colour();
  }

  private long now() {
    return settings.nanos().getAsLong();
  }

  private Duration elapsed() {
    return Duration.ofNanos(now() - phaseStarted);
  }

  /// The mutants that ran to a result a test decided: caught, missed or timed out.
  private int ran() {
    return counts.getOrDefault(Status.CAUGHT, 0)
        + counts.getOrDefault(Status.MISSED, 0)
        + counts.getOrDefault(Status.TIMED_OUT, 0);
  }

  private Snapshot snapshot() {
    var current = java.util.Objects.requireNonNull(phase);
    var tail = log;
    var elapsed = elapsed();
    return new Snapshot(
        current,
        doing,
        tail == null ? Optional.empty() : tail.latest(),
        done,
        total,
        counts,
        elapsed,
        current == Phase.MUTANTS ? workload.estimate(elapsed, ran()) : Snapshot.Estimate.NONE,
        workload.workers(now()),
        keys != null);
  }
}
