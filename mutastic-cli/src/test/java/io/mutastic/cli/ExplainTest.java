package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `mutastic explain`: the mutant's card as a run would show it, then what it is: the bytecode
/// change opcode by opcode and how the jump relates to the source, the operator, the id, the site,
/// and how the last full run went.
class ExplainTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");
  private static final String ID = "m855c8e3c";

  @TempDir Path work;

  @Test
  void theCardThenWhatTheMutantIs() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var before = Samples.mutastic("explain", ID, project.toString());
    Samples.mutastic("run", project.toString(), "--format=brief", "--report=none");
    var after = Samples.mutastic("explain", ID, project.toString());

    assertThat(before.exit()).isEqualTo(Main.PASSED);
    assertThat(before.out())
        .contains("[✗ MISSED] no test checks discount() at total == 100")
        .contains("void discountWithTotal100()")
        .contains("About this mutant")
        .contains("= bytecode  IF_ICMPLT → IF_ICMPLE")
        .contains("IF_ICMPLT: jump if the first int is less than the second")
        .contains(
            "javac compiles `total >= 100` to IF_ICMPLT, a jump past the body when `total < 100`")
        .contains("= id        " + ID + ": `m` and the first 8 hex digits of its fingerprint")
        .contains("= last run  none yet");
    assertThat(after.out()).contains("= last run  missed: every covering test still passed");
  }

  /// `next` runs every mutant the first time, so that's the last run too.
  @Test
  void nextCountsAsARun() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    Samples.mutastic("next", project.toString());
    var explained = Samples.mutastic("explain", ID, project.toString());

    assertThat(explained.out()).contains("= last run  missed: every covering test still passed");
  }
}
