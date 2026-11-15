package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A hooked `gate check` keeps within what the hook allows: it waits for the count of the task's
/// start only as long as its budget has left, and hands over when its last run took longer than the
/// whole budget. The budgets here are seconds, not the hook's ten minutes.
class GateBudgetTest {
  @TempDir Path work;

  @Test
  void theWaitNeverPushesTheCheckPastItsBudget() {
    var budget = new GateBudget(Duration.ofSeconds(3), Duration.ofSeconds(2), Optional.empty());

    for (long millis = 0; millis <= 5_000; millis += 250) {
      var elapsed = Duration.ofMillis(millis);
      var wait = budget.countWaitAfter(elapsed);
      assertThat(wait).isBetween(Duration.ZERO, budget.countWait());
      assertThat(elapsed.plus(wait))
          .as("%s in", elapsed)
          .isLessThanOrEqualTo(elapsed.compareTo(budget.total()) > 0 ? elapsed : budget.total());
    }
    assertThat(budget.countWaitAfter(Duration.ZERO)).isEqualTo(Duration.ofSeconds(2));
    assertThat(budget.countWaitAfter(Duration.ofMillis(2_500))).isEqualTo(Duration.ofMillis(500));
    assertThat(budget.countWaitAfter(Duration.ofSeconds(4))).isEqualTo(Duration.ZERO);
  }

  /// A count still running when the budget runs out is left: the check says it couldn't count.
  @Test
  void aSlowCountIsLeftWhenTheBudgetRunsOut() throws Exception {
    var slow = neverFinishing(work);
    try {
      var budget = new GateBudget(Duration.ofSeconds(2), Duration.ofSeconds(60), Optional.empty());

      long before = System.nanoTime();
      var counted = GateCount.waitFor(work, budget.countWaitAfter(Duration.ofSeconds(1)));
      var waited = Duration.ofNanos(System.nanoTime() - before);

      assertThat(counted.counted()).isFalse();
      assertThat(counted.why()).isEqualTo("the count didn't finish in 1s");
      assertThat(waited).isLessThan(Duration.ofSeconds(2));
    } finally {
      GateCount.stop(work);
      slow.destroyForcibly();
    }
  }

  /// A count, in the gate's work directory `work`, that runs until it's stopped: any count left
  /// there is stopped first.
  static Process neverFinishing(Path work) throws Exception {
    GateCount.stop(work);
    var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    var process =
        new ProcessBuilder(
                java, "-cp", System.getProperty("java.class.path"), Never.class.getName())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    var started = new Properties();
    started.setProperty("pid", String.valueOf(process.pid()));
    started.setProperty("copy", "");
    started.setProperty("token", "never");
    Files.createDirectories(work);
    try (var out =
        Files.newBufferedWriter(work.resolve(GateCount.STARTED), StandardCharsets.UTF_8)) {
      started.store(out, null);
    }
    return process;
  }

  /// A count that never finishes.
  static final class Never {
    private Never() {}

    public static void main(String[] args) throws InterruptedException {
      Thread.sleep(Duration.ofMinutes(10));
    }
  }

  @Test
  void aLastRunLongerThanTheBudgetOverruns() {
    var env = Map.of("MUTASTIC_GATE_BUDGET", "2");
    assertThat(GateBudget.of(env, work).overruns()).as("no run yet").isFalse();

    GateBudget.ran(work, Duration.ofMillis(1_500));
    assertThat(GateBudget.of(env, work).overruns()).isFalse();

    GateBudget.ran(work, Duration.ofMillis(2_001));
    var budget = GateBudget.of(env, work);
    assertThat(budget.overruns()).isTrue();
    assertThat(budget.overrun())
        .isEqualTo(
            "mutastic gate: this project's gate check takes longer than the hook allows (3s last"
                + " time; MUTASTIC_GATE_BUDGET allows 2s), so it's over to you: run `mutastic gate"
                + " check` yourself");
  }

  @Test
  void theDefaultsFitThePluginsHook() {
    var budget = GateBudget.of(Map.of(), work);

    assertThat(budget.total().toSeconds()).isEqualTo(600); // the hook's "timeout": 600
    assertThat(budget.countWait().toSeconds()).isEqualTo(240);
    assertThat(GateBudget.of(Map.of("MUTASTIC_GATE_COUNT_WAIT", "0"), work).countWait())
        .isEqualTo(Duration.ZERO);
  }

  /// Decision 5: a value it doesn't take is refused, not read as the default.
  @Test
  void aValueItDoesntTakeIsRefused() {
    assertThatThrownBy(() -> GateBudget.of(Map.of("MUTASTIC_GATE_BUDGET", "0"), work))
        .hasMessage("MUTASTIC_GATE_BUDGET is a whole number of seconds above 0, not 0");
    assertThatThrownBy(() -> GateBudget.of(Map.of("MUTASTIC_GATE_COUNT_WAIT", "4m"), work))
        .hasMessage("MUTASTIC_GATE_COUNT_WAIT is a whole number of seconds, not 4m");
  }
}
