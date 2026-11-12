package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A module's own `mutastic.yaml` is read however the module is run: from the build's top, as
/// `:core`, the same as from its own directory. Once, the top's run read only the top's file, and
/// the same module got two scores.
class ModuleConfigurationTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));
  private static final String REASON = "the fee table is checked by the app's tests";

  @TempDir Path work;

  @Test
  void gradleReadsTheModulesFileFromTheTop() throws Exception {
    assertTheModulesRuleHolds(Samples.copy(SAMPLES.resolve("modules"), work.resolve("modules")));
  }

  @Test
  void mavenReadsTheModulesFileFromTheTop() throws Exception {
    assertTheModulesRuleHolds(
        Samples.copy(SAMPLES.resolve("maven-modules"), work.resolve("maven-modules")));
  }

  private static void assertTheModulesRuleHolds(Path project) throws Exception {
    Files.writeString(
        project.resolve("core/mutastic.yaml"),
        "rules:\n  - skip: com.example.core.Fees\n    reason: %s\n".formatted(REASON));

    var run = Samples.mutastic("run", project.toString(), ":core", "--format=jsonl");

    assertThat(run.err()).doesNotContain("mutastic:");
    assertThat(run.out()).contains(REASON).doesNotContain("\"status\":\"Survived\"");
  }
}
