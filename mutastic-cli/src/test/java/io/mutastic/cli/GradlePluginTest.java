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
  private static final Path SUITES = Path.of(System.getProperty("mutastic.samples"), "suites");
  private static final Path REPOSITORY = Path.of(System.getProperty("mutastic.samplesRepository"));
  private static final String VERSION = System.getProperty("mutastic.version");
  private static final Pattern DONE_WHEN =
      Pattern.compile("\"doneWhen\":\"\\./gradlew (?<task>[^\"]*) passes\"");

  @TempDir Path work;

  @Test
  void theTasksRunAndTheCommandsOfferedAreTasks() throws Exception {
    var project = withPlugin(Samples.copy(SAMPLE, work.resolve("modules")));

    // The build file's threshold, 90, over the plugin's 80: core's 80% doesn't reach it.
    var fromTheBuildFile = gradlew(project, ":core:mutasticRun", "-Pmutastic.format=jsonl");

    assertThat(fromTheBuildFile.out())
        .contains("\"file\":\"core/src/main/java/com/example/core/Fees.java\"")
        .contains("\"threshold\":90,")
        .contains("the mutation score is below the threshold");
    assertThat(fromTheBuildFile.exit()).isEqualTo(1);

    // The command line's over the build file's, for one run.
    var run =
        gradlew(project, ":core:mutasticRun", "-Pmutastic.format=jsonl", "-Pmutastic.threshold=0");

    assertThat(run.out()).contains("\"threshold\":0,");
    assertThat(run.exit()).as(run.out()).isZero();
    var doneWhen = DONE_WHEN.matcher(run.out());
    assertThat(doneWhen.find()).as(run.out()).isTrue();
    var command = doneWhen.group("task");
    assertThat(command).startsWith(":core:mutasticVerify -Pmutastic.mutant=m");

    var verify = gradlew(project, command.split(" ", -1));

    assertThat(verify.out()).as(verify.out()).contains("is still missed");
    assertThat(verify.exit()).isEqualTo(1);
    sameSetupAsTheCommand(project);
  }

  /// `suite` in the build file picks the Test task whose tests run, as `--suite` does, and the
  /// command line's `-Pmutastic.suite` is over it; its setup is the init script's for that suite.
  @Test
  void theBuildFilesSuiteRunsThatTestTask() throws Exception {
    var project = suitesWithPlugin();

    var integration = gradlew(project, "mutasticRun", "-Pmutastic.format=agent");
    var unit = gradlew(project, "mutasticRun", "-Pmutastic.format=agent", "-Pmutastic.suite=test");

    assertThat(integration.out()).as(integration.out()).contains("0 missed");
    assertThat(unit.out()).as(unit.out()).contains("1 missed");
    gradlew(project, "mutasticWriteSetup");
    Samples.mutastic("run", project.toString(), "--suite=integrationTest", "--report=none");
    assertThat(setup(project.resolve("build/mutastic/setup.properties")))
        .isEqualTo(
            setup(project.resolve("build/mutastic/suites/integrationTest/setup.properties")));
  }

  /// The tasks work with the configuration cache and Isolated Projects: the first run stores the
  /// build's configuration and the second reuses it, with no problem reported by either (a problem
  /// fails the build), and what the second writes is the setup the init script writes.
  @Test
  void theConfigurationCacheKeepsTheTasks() throws Exception {
    var project = suitesWithPlugin();
    var cached =
        new String[] {
          "mutasticRun",
          "-Pmutastic.format=agent",
          "--configuration-cache",
          "-Dorg.gradle.unsafe.isolated-projects=true"
        };

    var stored = gradlewLoud(project, cached);
    var reused = gradlewLoud(project, cached);

    assertThat(stored.out()).as(stored.out()).contains("Configuration cache entry stored");
    assertThat(stored.exit()).as(stored.out()).isZero();
    assertThat(reused.out()).as(reused.out()).contains("Configuration cache entry reused");
    assertThat(reused.out()).contains("0 missed");
    assertThat(reused.exit()).as(reused.out()).isZero();
    Samples.mutastic("run", project.toString(), "--suite=integrationTest", "--report=none");
    assertThat(setup(project.resolve("build/mutastic/setup.properties")))
        .isEqualTo(
            setup(project.resolve("build/mutastic/suites/integrationTest/setup.properties")));
  }

  /// The suites sample with the plugin applied from the build's repository, and its build file
  /// choosing the `integrationTest` suite.
  private Path suitesWithPlugin() throws IOException {
    var project = Samples.copy(SUITES, work.resolve("suites"));
    var repository = REPOSITORY.toUri().toString();
    var settings = project.resolve("settings.gradle.kts");
    Files.writeString(
        settings,
        "pluginManagement {\n    repositories {\n        maven(\"%s\")\n        gradlePluginPortal()\n    }\n}\n"
                .formatted(repository)
            + Files.readString(settings));
    var build = project.resolve("build.gradle.kts");
    Files.writeString(
        build,
        Files.readString(build)
                .replace(
                    "plugins {\n    java\n}",
                    "plugins {\n    java\n    id(\"io.mutastic\") version \"%s\"\n}"
                        .formatted(VERSION))
                .replace(
                    "    mavenCentral()\n",
                    "    mavenCentral()\n    maven(\"%s\")\n".formatted(repository))
            + "\nmutastic {\n    suite = \"integrationTest\"\n}\n");
    return project;
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
  /// the `mutastic` command it runs; and a threshold of 90, in the build file.
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
                    """
                        apply(plugin = "java")
                        apply(plugin = "io.mutastic")
                        configure<io.mutastic.gradle.plugin.MutasticExtension> {
                            threshold = 90
                        }
                    """)
                .replace(
                    "        mavenCentral()\n",
                    "        mavenCentral()\n        maven(\"%s\")\n".formatted(repository)));
    return project;
  }

  private Samples.Result gradlew(Path project, String... arguments)
      throws IOException, InterruptedException {
    return gradlew(project, List.of("-q"), arguments);
  }

  /// With Gradle's own lines, which say what became of the configuration cache.
  private Samples.Result gradlewLoud(Path project, String... arguments)
      throws IOException, InterruptedException {
    return gradlew(project, List.of("--console=plain"), arguments);
  }

  private Samples.Result gradlew(Path project, List<String> logging, String... arguments)
      throws IOException, InterruptedException {
    var command = new ArrayList<>(List.of("./gradlew"));
    command.addAll(logging);
    // The plugin runs in the sample's Gradle daemon: measured there, when the build measures it,
    // in a daemon for this build alone, whose coverage is written as it exits.
    var agent = System.getProperty("mutastic.coverageAgent", "");
    if (!agent.isBlank()) {
      command.addAll(List.of("--no-daemon", "-Dorg.gradle.jvmargs=" + agent));
    }
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
