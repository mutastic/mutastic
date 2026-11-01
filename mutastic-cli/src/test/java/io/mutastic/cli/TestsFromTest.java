package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Another module's tests over this module's mutants (`samples/modules`, where only `:app`'s test
/// checks `:core`'s boundary): run as `:app`'s build runs them, with `:core`'s mutated classes in
/// place of its jar, and merged, a mutant caught by either being caught.
class TestsFromTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "modules");
  private static final String BOUNDARY = "\"status\":\"%s\",\"operator\":\"mutastic/boundary\"";

  @TempDir Path work;

  @Test
  void anotherModulesTestsCatchWhatItsOwnMiss() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("modules"));

    var own = Samples.mutastic("run", project.toString(), ":core", "--format=jsonl");
    var both =
        Samples.mutastic("run", project.toString(), ":core", "--tests-from=:app", "--format=jsonl");

    assertThat(own.out()).contains(BOUNDARY.formatted("Survived"));
    assertThat(both.out())
        .contains(BOUNDARY.formatted("Killed"))
        .contains("\"killedBy\":\"com.example.app.CheckoutTest#tenPaysAFee\"")
        .contains("\"sameCodeAsLastRun\":false")
        .contains("\"likelyFlaky\":[]");
  }
}
