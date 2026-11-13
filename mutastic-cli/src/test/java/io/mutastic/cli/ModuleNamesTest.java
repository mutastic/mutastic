package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A run that names no module of a multi-module build, or one it hasn't got, or a directory inside
/// a build: the message names the modules with Java code, and ends in the commands that test them,
/// which work as printed.
class ModuleNamesTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));

  @TempDir Path work;

  @Test
  void aMavenBuildsRootNamesItsModulesAsCommands() throws Exception {
    var build = Samples.copy(SAMPLES.resolve("maven-modules"), work.resolve("maven-modules"));

    var run = Samples.mutastic("run", build.toString());

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err())
        .endsWith(
            """
            mutastic: %1$s is a build's root, with no Java code of its own.
            Its modules with Java code are :app and :core. To test one:
              mutastic run %1$s :app
              mutastic run %1$s :core
            """
                .formatted(build));
    assertWorks(lastCommand(run));
  }

  @Test
  void aMavenModuleTheBuildHasNotGotIsNamedAndLeavesNothingBehind() throws Exception {
    var build = Samples.copy(SAMPLES.resolve("maven-modules"), work.resolve("maven-modules"));

    var run = Samples.mutastic("run", build.toString(), ":nope");

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err())
        .isEqualTo(
            """
            mutastic: the build has no module :nope.
            Its modules with Java code are :app and :core. To test one:
              mutastic run %1$s :app
              mutastic run %1$s :core
            """
                .formatted(build));
    assertThat(build.resolve("nope")).doesNotExist();
    assertWorks(lastCommand(run));
  }

  @Test
  void aNearMissIsSaidAndNotRun() throws Exception {
    var maven = Samples.copy(SAMPLES.resolve("maven-modules"), work.resolve("maven-modules"));
    var gradle = Samples.copy(SAMPLES.resolve("modules"), work.resolve("modules"));

    for (var build : List.of(maven, gradle)) {
      var run = Samples.mutastic("run", build.toString(), ":cor");

      assertThat(run.exit()).isEqualTo(Main.USAGE);
      // Gradle would take :cor for :core, and run it.
      assertThat(run.err())
          .endsWith(
              """
              mutastic: the build has no module :cor.
              There's one called :core. To test it:
                mutastic run %s :core
              """
                  .formatted(build));
      assertThat(run.out()).doesNotContain("Score");
    }
    assertWorks(List.of("run", gradle.toString(), ":core"));
  }

  @Test
  void aGradleModuleTheBuildHasNotGotIsNamedWithoutGradlesOutput() throws Exception {
    var build = Samples.copy(SAMPLES.resolve("modules"), work.resolve("modules"));

    var run = Samples.mutastic("run", build.toString(), ":nope");

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err())
        .endsWith(
            """
            mutastic: the build has no module :nope.
            Its modules with Java code are :app and :core. To test one:
              mutastic run %1$s :app
              mutastic run %1$s :core
            """
                .formatted(build))
        .doesNotContain("FAILURE", "--stacktrace");
    assertWorks(lastCommand(run));
  }

  @Test
  void aDirectoryInsideAGradleBuildNamesTheBuild() throws Exception {
    var build = Samples.copy(SAMPLES.resolve("pricing"), work.resolve("pricing"));

    var run = Samples.mutastic("run", build.resolve("src").toString());

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err())
        .isEqualTo(
            """
            mutastic: %s isn't a build's top directory.
            It's inside the Gradle build in %s. To test it:
              mutastic run %s
            """
                .formatted(build.resolve("src"), build, build));
    assertWorks(lastCommand(run));
  }

  /// A Maven module is tested from the top of its build, which builds the modules it uses.
  @Test
  void aDirectoryInsideAMavenModuleNamesTheModuleFromTheTop() throws Exception {
    var build = Samples.copy(SAMPLES.resolve("maven-modules"), work.resolve("maven-modules"));
    var inside = build.resolve("app/src/main");

    var run = Samples.mutastic("run", inside.toString());

    assertThat(run.err())
        .isEqualTo(
            """
            mutastic: %s isn't a build's top directory.
            It's inside module :app of the Maven build in %s. To test it:
              mutastic run %s :app
            """
                .formatted(inside, build, build));
    assertThat(build.resolve("app/target")).doesNotExist();
    assertWorks(lastCommand(run));
  }

  /// The words of the message's last line, a `mutastic` command, without `mutastic`.
  private static List<String> lastCommand(Samples.Result run) {
    var lines = run.err().strip().lines().toList();
    var command = lines.getLast().strip();
    assertThat(command).startsWith("mutastic run ");
    return List.of(command.substring("mutastic ".length()).split(" "));
  }

  /// The command runs the module's mutants: it gets past what the message was about.
  private static void assertWorks(List<String> command) {
    var args = new ArrayList<>(command);
    args.addAll(List.of("--format=brief", "--report=none"));

    var run = Samples.mutastic(args.toArray(String[]::new));

    assertThat(run.err()).as(String.join(" ", args)).doesNotContain("mutastic:");
    assertThat(run.exit()).isIn(Main.PASSED, Main.FAILED);
  }
}
