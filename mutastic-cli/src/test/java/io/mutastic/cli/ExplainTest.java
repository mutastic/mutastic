package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `mutastic explain`: everything known of one mutant, running no test, with how the last full run
/// went once there is one.
class ExplainTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");
  private static final String ID = "m855c8e3c";

  @TempDir Path work;

  @Test
  void explainsTheChangeTheGapAndTheLastRun() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var before = Samples.mutastic("explain", ID, project.toString());
    Samples.mutastic("run", project.toString(), "--format=brief", "--report=none");
    var after = Samples.mutastic("explain", ID, project.toString());

    assertThat(before.exit()).isEqualTo(Main.PASSED);
    assertThat(before.out())
        .startsWith(ID + " · mutastic/boundary · src/main/java/com/example/pricing/Pricing.java:17")
        .contains("   - if (total >= 100) {", "   + if (total > 100) {")
        .contains("   if missed: no test checks `discount()` at `total == 100`")
        .contains("   last run: none yet");
    assertThat(after.out())
        .contains("   last run: missed: every covering test still passed")
        .contains("   or ignore: mutastic ignore " + ID);
  }
}
