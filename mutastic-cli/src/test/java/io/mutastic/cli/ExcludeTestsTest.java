package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `--exclude-tests` on `samples/suggestions`: leaving out `WindowTest` leaves `Window` reached by
/// no test, and the summary says what was left out. A method no test reaches gets an assertion to
/// start its first test with.
class ExcludeTestsTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "suggestions");

  @TempDir Path work;

  @Test
  void aTestLeftOutIsNamedAndWhatOnlyItReachedGetsAStartingAssertion() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("suggestions"));

    var human =
        Samples.mutastic(
            java.util.Map.of("MUTASTIC_FORMAT", "full"),
            "run",
            project.toString(),
            "--exclude-tests=WindowTest");
    var agent =
        Samples.mutastic(
            "run",
            project.toString(),
            "--format=agent",
            "--exclude-tests=com.example.rates.WindowTest");

    assertThat(human.out())
        .contains("src/main/java/com/example/rates/Window.java:6 openOn() (12 mutants)")
        .contains("try: assertThat(new Window(")
        .contains("Left out  WindowTest (--exclude-tests)");
    assertThat(agent.out())
        .contains("12 not covered")
        .contains("  start with: assertThat(new Window(");
  }
}
