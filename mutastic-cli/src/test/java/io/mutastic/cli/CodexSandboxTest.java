package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A build that Codex's sandbox refused says so, and what to do; nothing else does.
class CodexSandboxTest {
  private static final Map<String, String> SANDBOXED = Map.of("CODEX_SANDBOX", "seatbelt");

  /// What Codex 0.167.0's sandbox made the Gradle wrapper say on the pricing sample.
  private static final String REFUSED =
      "Exception in thread \"main\" java.io.FileNotFoundException:"
          + " /Users/someone/.gradle/wrapper/dists/gradle-9.2.1-bin/x/gradle-9.2.1-bin.zip.lck"
          + " (Operation not permitted)";

  @TempDir Path work;

  /// A Gradle build whose wrapper prints `output` and fails, as it did in the sandbox.
  private Path buildThatFails(String output) throws IOException {
    var project = Files.createDirectories(work.resolve("project"));
    Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"project\"\n");
    var gradlew = project.resolve("gradlew");
    Files.writeString(gradlew, "#!/bin/sh\necho '" + output + "'\nexit 1\n");
    assertThat(gradlew.toFile().setExecutable(true)).isTrue();
    return project;
  }

  @Test
  void aBuildTheSandboxRefusedSaysToRunMutasticOutsideIt() throws IOException {
    var project = buildThatFails(REFUSED);

    var run = Samples.mutastic(SANDBOXED, "run", project.toString());

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err())
        .contains(
            "Gradle couldn't build",
            "(Operation not permitted)",
            "mutastic: this ran in Codex's sandbox, which refused the build",
            "prefix_rule(pattern = [\"mutastic\"], decision = \"allow\")");
  }

  @Test
  void outsideTheSandboxTheSameFailureGetsNoHint() throws IOException {
    var project = buildThatFails(REFUSED);

    var run = Samples.mutastic("run", project.toString());

    assertThat(run.err()).contains("Gradle couldn't build").doesNotContain("Codex");
  }

  @Test
  void aBuildThatFailsForItsOwnReasonsInTheSandboxGetsNoHint() throws IOException {
    var project = buildThatFails("e: build.gradle.kts:1:1: Unresolved reference: this");

    var run = Samples.mutastic(SANDBOXED, "run", project.toString());

    assertThat(run.err()).contains("Gradle couldn't build").doesNotContain("Codex");
  }
}
