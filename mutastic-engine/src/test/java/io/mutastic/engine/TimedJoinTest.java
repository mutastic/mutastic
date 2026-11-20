package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.engine.ClaimFixture.Inputs;
import org.junit.jupiter.api.Test;

/// A removed `join` with a time limit hides the race one with no limit shows: the code reads what
/// the thread does before it's done. Its mutant runs, and a test that reads the result straight
/// after catches it. How long it waits, and a removed sleep, stay arid.
class TimedJoinTest {
  private final ClaimFixture removed =
      new ClaimFixture("waits/Joins.java", "mutastic/remove-void-call", Inputs.NUMBERS);
  private final ClaimFixture waits =
      new ClaimFixture("waits/Joins.java", "mutastic/constant-value", Inputs.NUMBERS);

  @Test
  void aRemovedTimedJoinRunsAndATestThatReadsTheResultCatchesIt() throws Exception {
    var join =
        removed.mutants().stream()
            .filter(mutant -> mutant.original().contains("java/lang/Thread.join(J)V"))
            .findFirst()
            .orElseThrow();

    assertThat(join.skipped()).isEmpty();
    var joined = removed.mutated(join).getDeclaredMethod("joined", int.class, int.class);
    joined.setAccessible(true);
    assertThat(joined.invoke(null, 3, 0)).isEqualTo(0);
    assertThat(removed.changesIn("joined")).contains("remove call (arid: only waits)");
  }

  @Test
  void howLongItWaitsStaysArid() {
    assertThat(waits.changesIn("joined"))
        .contains("constant 5000 to 5001 (arid: only sets how long to wait)");
  }
}
