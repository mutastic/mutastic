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

  /// The boundary mutant, `amount < 10` becoming `<=` in `Fees.fee()`.
  private static final String BOUNDARY_ID = "mac8091e6";

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
        .contains("\"caughtBy\":\"com.example.app.CheckoutTest#tenPaysAFee\"")
        .contains("\"sameCodeAsLastRun\":false")
        .contains("\"likelyFlaky\":[]");
  }

  /// `verify` runs them as a run does: a mutant only another module's tests catch passes it there,
  /// and still fails it without them.
  @Test
  void verifyRunsThemToo() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("modules"));

    var own = Samples.mutastic("verify", BOUNDARY_ID, project.toString(), ":core");
    var both =
        Samples.mutastic("verify", BOUNDARY_ID, project.toString(), ":core", "--tests-from=:app");

    assertThat(own.out()).contains(BOUNDARY_ID + " is still missed");
    assertThat(own.exit()).isEqualTo(Main.FAILED);
    assertThat(both.out()).as(both.err()).contains(BOUNDARY_ID + " is caught by");
    assertThat(both.exit()).isEqualTo(Main.PASSED);
  }

  /// `dependents`: every module that depends on this one, as each build tool's own graph says.
  @Test
  void dependentsAreFoundByGradle() throws Exception {
    assertDependentsCatchTheBoundary(Samples.copy(SAMPLE, work.resolve("modules")), ":core");
  }

  @Test
  void dependentsAreFoundByMaven() throws Exception {
    assertDependentsCatchTheBoundary(
        Samples.copy(SAMPLE.resolveSibling("maven-modules"), work.resolve("maven-modules")),
        ":core");
  }

  private static void assertDependentsCatchTheBoundary(Path project, String module) {
    var run =
        Samples.mutastic(
            "run", project.toString(), module, "--tests-from=dependents", "--format=jsonl");

    assertThat(run.err()).doesNotContain("mutastic:");
    assertThat(run.out()).contains(BOUNDARY.formatted("Killed"));
  }
}
