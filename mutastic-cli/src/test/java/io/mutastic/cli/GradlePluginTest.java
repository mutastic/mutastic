package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The Gradle plugin, applied to `samples/modules` and run by its own Gradle, resolved from a
/// repository in the build as it would be from a public one. `:core`'s boundary is checked only by
/// `:app`'s test, so it's missed, and the command the run offers to prove a fix is a task that runs
/// as it's copied. The plugin reads the build as the `mutastic` command's init script does.
class GradlePluginTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "modules");
  private static final Path REPOSITORY = Path.of(System.getProperty("mutastic.samplesRepository"));
  private static final String VERSION = System.getProperty("mutastic.version");
  private static final Pattern DONE_WHEN =
      Pattern.compile("\"doneWhen\":\"\\./gradlew (?<task>[^\"]*) passes\"");

  @TempDir Path work;

  @Test
  void theTasksRunAndTheCommandsOfferedAreTasks() throws Exception {
    var project = withPlugin(Samples.copy(SAMPLE, work.resolve("modules")));

    var run =
        gradlew(project, ":core:mutasticRun", "-Pmutastic.format=jsonl", "-Pmutastic.threshold=90");

    // 80%, below the threshold, so the task fails after reporting.
    assertThat(run.out()).contains("\"file\":\"core/src/main/java/com/example/core/Fees.java\"");
    assertThat(run.exit()).as(run.out()).isEqualTo(1);
    assertThat(run.out()).contains("the mutation score is below the threshold");
    var doneWhen = DONE_WHEN.matcher(run.out());
    assertThat(doneWhen.find()).as(run.out()).isTrue();
    var command = doneWhen.group("task");
    assertThat(command).startsWith(":core:mutasticVerify -Pmutastic.mutant=m");

    var verify = gradlew(project, command.split(" ", -1));

    assertThat(verify.out()).as(verify.out()).contains("is still missed");
    assertThat(verify.exit()).isEqualTo(1);
    sameSetupAsTheCommand(project);
  }

  /// The plugin's setup and the init script's, for the same module, say the same.
  private static void sameSetupAsTheCommand(Path project) throws IOException {
    var fromCommand = Samples.mutastic("run", project.toString(), ":core", "--report=none");

    assertThat(fromCommand.exit()).as(fromCommand.err()).isIn(Main.PASSED, Main.FAILED);
    assertThat(setup(project.resolve("core/build/mutastic/setup.properties")))
        .isEqualTo(setup(project.resolve("build/mutastic/core/setup.properties")));
  }

  private static Properties setup(Path file) throws IOException {
    var setup = new Properties();
    try (InputStream in = Files.newInputStream(file)) {
      setup.load(in);
    }
    return setup;
  }

  /// The sample with the plugin applied to each module, from the build's repository, which also has
  /// the `mutastic` command it runs.
  private static Path withPlugin(Path project) throws IOException {
    var repository = REPOSITORY.toUri().toString();
    var settings = project.resolve("settings.gradle.kts");
    Files.writeString(
        settings,
        """
        pluginManagement {
            repositories {
                maven("%s")
                gradlePluginPortal()
            }
        }
        """
                .formatted(repository)
            + Files.readString(settings));
    var build = project.resolve("build.gradle.kts");
    Files.writeString(
        build,
        """
        plugins {
            id("io.mutastic") version "%s" apply false
        }

        """
                .formatted(VERSION)
            + Files.readString(build)
                .replace(
                    "    apply(plugin = \"java\")\n",
                    "    apply(plugin = \"java\")\n    apply(plugin = \"io.mutastic\")\n")
                .replace(
                    "        mavenCentral()\n",
                    "        mavenCentral()\n        maven(\"%s\")\n".formatted(repository)));
    return project;
  }

  private Samples.Result gradlew(Path project, String... arguments)
      throws IOException, InterruptedException {
    var command = new ArrayList<>(List.of("./gradlew", "-q"));
    command.addAll(List.of(arguments));
    var log = work.resolve("gradle.log");
    int exit =
        new ProcessBuilder(command)
            .directory(project.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start()
            .waitFor();
    return new Samples.Result(exit, Files.readString(log, StandardCharsets.UTF_8), "");
  }
}
