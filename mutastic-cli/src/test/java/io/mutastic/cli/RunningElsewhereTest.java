package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Commands copied from the output, and commands run in the wrong place: the commands name the
/// project they're for, and a run in the wrong place says what's wrong and what to run.
class RunningElsewhereTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");
  private static final String ID = "m855c8e3c";

  @TempDir
  Path work;

  @Test
  void theCommandsOfferedNameTheProject() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var run = Samples.mutastic("run", project.toString(), "--format=full", "--report=none");

    // The tests run from elsewhere, so the project is named by its full path.
    assertThat(run.out()).contains("done when  mutastic verify %s %s passes".formatted(ID, project))
        .contains("reproduce  mutastic run --only=%s %s".formatted(ID, project))
        .contains("Next: mutastic verify <id> %1$s · mutastic run --only=<id> %1$s".formatted(project));
  }

  @Test
  void oneMutantByIdIsJudgedByItsFateNotTheScore() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var missed = Samples.mutastic("run", "--only=" + ID, project.toString(), "--format=concise", "--report=none");
    var jsonl = Samples.mutastic("run", "--only=" + ID, project.toString(), "--format=jsonl", "--report=none");

    assertThat(missed.exit()).isEqualTo(Main.FAILED);
    assertThat(missed.out()).contains(" [MISSED] %s is still missed · done when mutastic verify %s %s passes"
        .formatted(ID, ID, project)).doesNotContain("Score", "threshold");
    assertThat(jsonl.out()).contains("\"passed\":false,\"only\":\"" + ID + "\"");
  }

  @Test
  void aBuildWhoseRootHasNoJavaNamesTheModulesThatDo() throws Exception {
    var build = work.resolve("multi");
    Samples.copy(SAMPLE, build.resolve("app"));
    Files.delete(build.resolve("app/settings.gradle.kts"));
    Files.move(build.resolve("app/gradlew"), build.resolve("gradlew"));
    Files.move(build.resolve("app/gradle"), build.resolve("gradle"));
    Files.writeString(build.resolve("settings.gradle.kts"), """
        plugins {
            id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
        }
        rootProject.name = "multi"
        include("app")
        """);

    var run = Samples.mutastic("run", "--only=" + ID, build.toString());

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err()).contains("the root project of " + build + " has no Java code for mutastic to test.",
        "Its modules with Java code are :app.", "  mutastic run %s :app".formatted(build),
        "  mutastic run --only=<id> %s :app".formatted(build), "A mutant's id belongs to the project its run was on.");
  }

  @Test
  void aDirectoryThatIsNotAGradleBuildSaysSoAndIsLeftAlone() throws Exception {
    var empty = Files.createDirectories(work.resolve("not-a-build"));

    var run = Samples.mutastic("run", "--only=" + ID, empty.toString());

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err()).contains(empty + " isn't a Gradle build", "mutastic run --only=<id> <project directory> [:module]",
        "A mutant's id belongs to the project its run was on.");
    try (var files = Files.list(empty)) {
      assertThat(files).isEmpty(); // no build/mutastic left behind
    }
  }
}
