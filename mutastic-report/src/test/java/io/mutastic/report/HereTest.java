package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HereTest {
  private static final Path SAMPLES = Path.of("/work/samples").toAbsolutePath();

  @Test
  void aFileBelowIsTypedFromHere() {
    assertThat(Here.shown(SAMPLES, SAMPLES.resolve("testng/build/mutastic/mutation.html")))
        .isEqualTo("testng/build/mutastic/mutation.html");
  }

  @Test
  void aFileElsewhereIsWhole() {
    var elsewhere = SAMPLES.resolveSibling("other/build/mutastic/mutation.html");

    assertThat(Here.shown(SAMPLES, elsewhere)).isEqualTo(elsewhere.toString());
  }

  @Test
  void aLogInAMessageIsShownFromHereAndLinkedWhole() {
    var log = Path.of("").toAbsolutePath().resolve("pricing/build/mutastic/worker.log");
    var said = "the test worker didn't start; its output is in " + log + ".\nmore";

    assertThat(Here.logs(said, Style.PLAIN))
        .isEqualTo(
            "the test worker didn't start; its output is in pricing/build/mutastic/worker.log.\n"
                + "more");
    assertThat(Here.logs(said, Style.PLAIN.withLinks()))
        .contains(
            "its output is in \u001b]8;;"
                + log.toUri()
                + "\u001b\\pricing/build/mutastic/worker.log\u001b]8;;\u001b\\.\n");
  }

  @Test
  void aLogNamedRelativeThatIsntThereIsLeftAsSaid() {
    var said = "Their output is in build/nowhere/tests.log.";

    assertThat(Here.logs(said, Style.PLAIN.withLinks())).isEqualTo(said);
  }
}
