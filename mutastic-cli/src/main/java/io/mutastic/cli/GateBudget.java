package io.mutastic.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/// The time a hooked `gate check` has, and what it spends it on.
///
/// The agents' plugins run `gate check` from a Stop hook that's stopped after ten minutes, and a
/// hook stopped gates nothing. So a hooked check waits for the count of the task's start
/// ([GateCount]) only as long as its budget has left once its own run is done, and leaves the count
/// for what the annotations say when that's not enough. When the last check's own run took longer
/// than the whole budget, a hooked check hands over before it runs, saying so, rather than be
/// stopped with nothing said.
///
/// @param total what a hooked check may take, from `MUTASTIC_GATE_BUDGET`: what the hook allows
/// @param countWait the longest a check waits for the count, from `MUTASTIC_GATE_COUNT_WAIT`
/// @param lastRun how long the last check's own run took, its wait for the count left out; none
///   before a check has run the tests here
record GateBudget(Duration total, Duration countWait, Optional<Duration> lastRun) {
  /// What the plugins' Stop hook allows a check: `"timeout": 600`.
  static final Duration TOTAL = Duration.ofMinutes(10);

  /// Within a hook's ten minutes, with the check's own run.
  static final Duration COUNT_WAIT = Duration.ofMinutes(4);

  /// The last check's own run, in the module's work directory: kept from task to task, as a task's
  /// first check is often its only one.
  static final String RECORD = "gate-check.properties";

  static final String BUDGET_VARIABLE = "MUTASTIC_GATE_BUDGET";

  static final String WAIT_VARIABLE = "MUTASTIC_GATE_COUNT_WAIT";

  /// The budget the environment sets, with the last run kept in `work`. A value that isn't a whole
  /// number of seconds is refused (decision 5), as is a budget of none.
  static GateBudget of(Map<String, String> env, Path work) {
    return new GateBudget(
        seconds(env, BUDGET_VARIABLE, TOTAL, 1),
        seconds(env, WAIT_VARIABLE, COUNT_WAIT, 0),
        lastRun(work));
  }

  /// Whether the last check's own run took longer than the whole budget: this one would be stopped
  /// before it said anything.
  boolean overruns() {
    return lastRun.filter(run -> run.compareTo(total) > 0).isPresent();
  }

  /// How long a check `elapsed` into its budget, its own run done, waits for the count: never so
  /// long that it passes the budget, and never longer than [#countWait].
  Duration countWaitAfter(Duration elapsed) {
    var left = total.minus(elapsed);
    return left.isNegative() ? Duration.ZERO : min(left, countWait);
  }

  /// What a hook is told when [#overruns]: the person's to run, as they can wait for it.
  String overrun() {
    return ("mutastic gate: this project's gate check takes longer than the hook allows (%ds last"
            + " time; %s allows %ds), so it's over to you: run `mutastic gate check` yourself")
        .formatted(roundedUp(lastRun.orElse(Duration.ZERO)), BUDGET_VARIABLE, total.toSeconds());
  }

  /// Keeps how long a check's own run took, for the next check's [#lastRun].
  static void ran(Path work, Duration took) {
    var record = new Properties();
    record.setProperty("took-ms", String.valueOf(took.toMillis()));
    try {
      Files.createDirectories(work);
      try (var out = Files.newBufferedWriter(work.resolve(RECORD), StandardCharsets.UTF_8)) {
        record.store(out, "mutastic gate: how long the last check's own run took");
      }
    } catch (IOException e) {
      // an estimate, not a result: with none kept, the next check has no estimate, as the first
    }
  }

  private static Optional<Duration> lastRun(Path work) {
    var file = work.resolve(RECORD);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    var record = new Properties();
    try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      record.load(in);
      return Optional.of(Duration.ofMillis(Long.parseLong(record.getProperty("took-ms", ""))));
    } catch (IOException | NumberFormatException e) {
      return Optional.empty(); // an unreadable record is no record: the next run writes a new one
    }
  }

  /// `variable`'s whole number of seconds, at least `least`; `otherwise` when it's unset.
  private static Duration seconds(
      Map<String, String> env, String variable, Duration otherwise, long least) {
    var given = env.getOrDefault(variable, "");
    if (given.isEmpty()) {
      return otherwise;
    }
    try {
      long seconds = Long.parseLong(given);
      if (seconds >= least) {
        return Duration.ofSeconds(seconds);
      }
    } catch (NumberFormatException e) {
      // refused below, as a value below the least is
    }
    throw new Main.ConfigException(
        "environment",
        "%s is a whole number of seconds%s, not %s"
            .formatted(variable, least > 0 ? " above 0" : "", given));
  }

  private static long roundedUp(Duration duration) {
    return (duration.toMillis() + 999) / 1000;
  }

  private static Duration min(Duration one, Duration other) {
    return one.compareTo(other) <= 0 ? one : other;
  }
}
