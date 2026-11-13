package io.mutastic.cli;

import io.mutastic.api.BuildTool;
import io.mutastic.api.CoveragePass;
import io.mutastic.api.Listener;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Status;
import java.io.PrintStream;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/// What's happening, on stderr, so stdout stays the result: a line as each phase ends, and while it
/// runs, a live line redrawn in place on a terminal, or a heartbeat line elsewhere once the last
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

  /// How often the live line is redrawn, and a heartbeat's age checked.
  static final Duration TICK = Duration.ofMillis(100);

  /// How progress looks and keeps time.
  ///
  /// @param colour the live line's phase in bold and the build tool's line dim
  /// @param heartbeat how old the last line may get, off a terminal, before a heartbeat
  /// @param nanos the clock: [System#nanoTime] but in tests
  /// @param ticking whether a ticker redraws and beats; tests call [#tick] themselves
  record Settings(boolean colour, Duration heartbeat, LongSupplier nanos, boolean ticking) {
    /// From `MUTASTIC_HEARTBEAT`, in seconds.
    static Settings of(Map<String, String> env, boolean colour) {
      return new Settings(colour, heartbeat(env), System::nanoTime, true);
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
      throw new Main.UsageException(
          "MUTASTIC_HEARTBEAT is a number of seconds above 0, not " + given);
    }
  }

  private final Object lock = new Object();
  private final Mode mode;
  private final Settings settings;
  private final ProgressStream stream;

  /// Prints each source file's line as it finishes, for people; none for agents and JSONL.
  private @Nullable Function<List<MutantResult>, String> fileLines;

  private @Nullable Ticker ticker;
  private @Nullable Phase phase;
  private long phaseStarted;
  private String doing = "";
  private @Nullable LogTail log;
  private int done;
  private int total;
  private int frame;
  private final Map<Status, Integer> counts = new EnumMap<>(Status.class);

  /// Each mutant's latest result, by id: a revised one replaces its first.
  private final Map<String, MutantResult> results = new HashMap<>();

  Progress(PrintStream err, Mode mode, Settings settings) {
    this.mode = mode;
    this.settings = settings;
    this.stream = new ProgressStream(err, settings.nanos(), settings.heartbeat());
  }

  /// From now on, each source file's line as it finishes: once the sources are known.
  void showFiles(Function<List<MutantResult>, String> lines) {
    this.fileLines = lines;
  }

  /// The first line, before anything slow; and the ticker starts.
  void started(String version, String buildTool) {
    synchronized (lock) {
      say("mutastic %s · reading the %s build…\n".formatted(version, buildTool));
      enter(Phase.READING, "the " + buildTool + " build");
      if (mode != Mode.NONE && settings.ticking() && ticker == null) {
        ticker = Ticker.every(TICK, this::tick);
      }
    }
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

  /// `1 test`, `3 tests`.
  private static String count(int number, String noun) {
    return number + " " + noun + (number == 1 ? "" : "s");
  }

  @Override
  public void coverageFinished(CoveragePass pass) {
    synchronized (lock) {
      enter(Phase.MUTANTS, "");
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
  public void mutantFinished(MutantResult result) {
    synchronized (lock) {
      done++;
      counts.merge(result.status(), 1, Integer::sum);
      results.put(result.mutant().id(), result);
    }
  }

  /// A source file's line, and its misses under it, as soon as its last mutant is done.
  @Override
  public void fileFinished(String topLevelClass, List<MutantResult> results) {
    var lines = fileLines;
    if (lines != null) {
      synchronized (lock) {
        say(lines.apply(results));
      }
    }
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

  /// The run's work is done: the last phase's line, and the live line gone, so the result starts on
  /// a clean line.
  void finished() {
    synchronized (lock) {
      leave();
      stream.clear();
    }
  }

  /// Progress stops: the live line goes, and nothing more is said.
  @Override
  public void close() {
    var running = ticker;
    if (running != null) {
      running.close();
      ticker = null;
    }
    synchronized (lock) {
      phase = null;
      stream.clear();
    }
  }

  /// What the ticker does: the live line redrawn, or a heartbeat if one is due.
  void tick() {
    synchronized (lock) {
      if (phase == null) {
        return;
      }
      if (mode == Mode.BAR) {
        stream.draw(Frames.live(snapshot(), elapsed(), frame++, settings.colour()));
      } else if (mode == Mode.COUNTER && stream.heartbeatDue()) {
        stream.line(Frames.heartbeat(snapshot(), elapsed()));
      }
    }
  }

  /// A permanent line, under which the live line is drawn again at once.
  private void say(String text) {
    if (mode == Mode.NONE) {
      return;
    }
    stream.line(text);
    if (mode == Mode.BAR && phase != null) {
      stream.draw(Frames.live(snapshot(), elapsed(), frame, settings.colour()));
    }
  }

  /// A new phase, its clock at 0, once the last has said how it ended.
  private void enter(Phase next, String what) {
    leave();
    phase = next;
    phaseStarted = settings.nanos().getAsLong();
    doing = what;
    log = null;
    done = 0;
    total = 0;
  }

  /// The line a phase ends with, where it isn't said as the next starts: every mutant's first
  /// result, and what was checked after them.
  private void leave() {
    int missed = counts.getOrDefault(Status.MISSED, 0);
    var ended =
        switch (phase) {
          case MUTANTS -> Frames.ran(snapshot(), elapsed());
          case CHECKING -> missed > 0 ? Frames.checked(missed, suggested(), elapsed()) : "";
          case null, default -> "";
        };
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

  private Duration elapsed() {
    return Duration.ofNanos(settings.nanos().getAsLong() - phaseStarted);
  }

  private Snapshot snapshot() {
    var tail = log;
    return new Snapshot(
        java.util.Objects.requireNonNull(phase),
        doing,
        tail == null ? Optional.empty() : tail.latest(),
        done,
        total,
        counts);
  }
}
