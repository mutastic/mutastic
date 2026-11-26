package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `samples/no-line-numbers`: a class compiled with `-g:none`. Coverage is recorded by line, so
/// every mutant read as "no test reaches this line", on line 1, and the score was a false 0%
/// (corpus survey A10); now the run refuses, naming the class and how to compile it.
class NoLineNumbersTest {
  private static final Path SAMPLE =
      Path.of(System.getProperty("mutastic.samples"), "no-line-numbers");

  @TempDir Path work;

  @Test
  void theRunRefusesNamingTheClass() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("no-line-numbers"));

    var run =
        Samples.mutastic(
            Map.of("MUTASTIC_FORMAT", "jsonl"), "run", project.toString(), "--report=none");

    assertThat(run.exit()).as(run.err()).isEqualTo(Main.USAGE);
    assertThat(run.out())
        .contains("\"reason\":\"run-failed\"")
        .contains("com.example.fees.Fees was compiled without line numbers")
        .contains("take -g:none out of the compiler's arguments")
        .doesNotContain("\"reason\":\"mutant-finished\"");
  }
}
