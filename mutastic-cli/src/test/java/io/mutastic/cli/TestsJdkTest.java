package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `samples/toolchain-8`: tests on a JDK 8 toolchain, older than the 17 mutastic's test workers run
/// on. The worker stopped on `-Xlog:cds=off`, which JDK 8 doesn't know, and read as "internal
/// error: the test worker didn't start", exit 4 (corpus survey A7b); now a refusal naming the JDK.
class TestsJdkTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "toolchain-8");

  @TempDir Path work;

  @Test
  void aJdk8ToolchainIsNamed() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("toolchain-8"));

    var run =
        Samples.mutastic(
            java.util.Map.of("MUTASTIC_FORMAT", "jsonl"),
            "run",
            project.toString(),
            "--report=none");

    assertThat(run.exit()).as(run.err()).isEqualTo(Main.USAGE);
    assertThat(run.out())
        .contains("\"code\":\"build-jdk\"")
        .contains("toolchain-8's tests run on JDK 8, ")
        .contains("mutastic's test workers run on JDK 17 or later")
        .doesNotContain("internal error");
  }
}
