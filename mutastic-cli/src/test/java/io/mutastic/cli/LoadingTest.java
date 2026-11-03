package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Code that runs only while a class loads (`samples/loading`): a table parsed into a static field.
/// A mutant swapped into a class already loaded would never run there and look missed, so these run
/// with the class loaded anew, against every test that uses it.
class LoadingTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "loading");

  @TempDir Path work;

  @Test
  void mutantsInCodeRunOnlyAtClassLoadAreCaught() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("loading"));

    var events = Samples.mutastic("run", project.toString(), "--format=jsonl", "--report=none");

    assertThat(events.out())
        .contains("\"Killed\":7,\"Survived\":0")
        .contains("\"coveredBy\":[\"com.example.loading.RatesTest#");
    assertThat(events.exit()).as(events.out()).isEqualTo(Main.PASSED);
  }
}
