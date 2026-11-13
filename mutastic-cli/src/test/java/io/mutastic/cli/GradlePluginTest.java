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
  private static final Path KOTLIN_MULTIPLATFORM =
      Path.of(System.getProperty("mutastic.samples"), "kotlin-multiplatform");
  private static final Path REPOSITORY = Path.of(System.getProperty("mutastic.samplesRepository"));
  private static final String VERSION = System.getProperty("mutastic.version");

  /// The wrapper as the plugin prints it: `./gradlew`, or `.\gradlew.bat` on Windows, escaped in
  /// JSON.
  private static final Pattern DONE_WHEN =
      Pattern.compile(
          "\"doneWhen\":\"(?<wrapper>\\./gradlew|\\.\\\\\\\\gradlew\\.bat) (?<task>[^\"]*) passes\"");

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

  /// A Kotlin Multiplatform module never applies the java plugin: the plugin reads its one JVM
  /// target instead, as the init script does, and the target's tests run on its code, its common
  /// code included.
  @Test
  void aKotlinMultiplatformModuleGetsTheTasks() throws Exception {
    var project = Samples.copy(KOTLIN_MULTIPLATFORM, work.resolve("kotlin-multiplatform"));
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
                "    kotlin(\"multiplatform\") version \"2.2.21\"\n",
                "    kotlin(\"multiplatform\") version \"2.2.21\"\n    id(\"io.mutastic\") version \"%s\"\n"
                    .formatted(VERSION))
            .replace(
                "    mavenCentral()\n",
                "    mavenCentral()\n    maven(\"%s\")\n".formatted(repository)));

    var run = gradlew(project, "mutasticRun", "-Pmutastic.format=jsonl", "-Pmutastic.threshold=0");

    assertThat(run.exit()).as(run.out() + run.err()).isZero();
    assertThat(run.out())
        .contains("\"reason\":\"run-finished\"")
        .contains("\"file\":\"src/commonMain/kotlin/com/example/tally/Tally.common.kt\"")
        .contains("com.example.tally.TallyTest#aHighScoreGetsTheBonus");
  }

  /// Gradle reads the command's stderr through a pipe, and prints it a line at a time: progress
  /// there is a line as each phase ends and a heartbeat whenever the last line is old enough, never
  /// a line redrawn in place.
  @Test
  void throughGradleProgressIsLinesAndHeartbeats() throws Exception {
    var project = withPlugin(Samples.copy(SAMPLE, work.resolve("modules")));

    var run =
        gradlew(
            project,
            java.util.Map.of("MUTASTIC_HEARTBEAT", "0.2"),
            List.of("-q"),
            ":core:mutasticRun",
            "-Pmutastic.threshold=0");

    assertThat(run.exit()).as(run.out()).isZero();
    assertThat(run.out())
        .as(run.out())
        .contains("built     ", "coverage  ", "ran       ")
        .doesNotContain("\u001b[");
    assertThat(run.out().lines()).as(run.out()).anyMatch(line -> HEARTBEAT.matcher(line).matches());
  }

  /// A heartbeat: a phase, what it's doing, and its clock.
  private static final Pattern HEARTBEAT =
      Pattern.compile(
          "(?<phase>building|coverage|mutants|checking) +(?<doing>.+) · (?<clock>\\d+s)");

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

  /// On a fresh checkout, the setup builds what the suite's classpath names, not only the module's
  /// classes: with `java-library`, `:app`'s tests run against `:core`'s jar, which compiling `:app`
  /// never builds. Without it, every test fails before any mutant. Building it doesn't run the Test
  /// task.
  @Test
  void aFreshCheckoutBuildsWhatTheTestsRunWith() throws Exception {
    var fromCommand = asLibraries(Samples.copy(SAMPLE, work.resolve("command")));
    var fromPlugin = asLibraries(withPlugin(Samples.copy(SAMPLE, work.resolve("plugin"))));

    var run = Samples.mutastic("run", fromCommand.toString(), ":app", "--report=none");
    var written = gradlew(fromPlugin, ":app:mutasticWriteSetup");

    assertThat(run.exit()).as(run.err()).isEqualTo(Main.PASSED);
    assertThat(written.exit()).as(written.out()).isZero();
    // `:core`'s jar is another module of the build: loaded with `:app`'s own classes.
    var setup = setup(fromPlugin.resolve("app/build/mutastic/setup.properties"));
    assertThat(setup.getProperty("alongside")).contains("core.jar");
    assertThat(setup.getProperty("dependencies")).doesNotContain("core.jar");
    var classpath =
        setup.getProperty("dependencies")
            + java.io.File.pathSeparator
            + setup.getProperty("alongside");
    for (String entry : classpath.split(java.io.File.pathSeparator, -1)) {
      assertThat(Path.of(entry)).exists();
    }
    // What the tests need is built, but the Test task itself never runs.
    assertThat(fromCommand.resolve("app/build/test-results")).doesNotExist();
    assertThat(fromPlugin.resolve("app/build/test-results")).doesNotExist();
  }

  /// The sample's modules as libraries, as most multi-module builds have them. (Run after
  /// [#withPlugin], which finds the modules by their `java` plugin.)
  private static Path asLibraries(Path project) throws IOException {
    var build = project.resolve("build.gradle.kts");
    Files.writeString(
        build,
        Files.readString(build)
            .replace("apply(plugin = \"java\")", "apply(plugin = \"java-library\")"));
    return project;
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
    return gradlew(project, java.util.Map.of(), logging, arguments);
  }

  /// With `env` added to Gradle's environment, which the command it runs inherits.
  private Samples.Result gradlew(
      Path project, java.util.Map<String, String> env, List<String> logging, String... arguments)
      throws IOException, InterruptedException {
    // Windows starts a program by its file's name, the wrapper's batch file there.
    var wrapper = java.io.File.separatorChar == '\\' ? "gradlew.bat" : "gradlew";
    var command = new ArrayList<>(List.of(project.resolve(wrapper).toString()));
    command.addAll(logging);
    // The plugin runs in the sample's Gradle daemon: measured there, when the build measures it,
    // in a daemon for this build alone, whose coverage is written as it exits.
    var agent = System.getProperty("mutastic.coverageAgent", "");
    if (!agent.isBlank()) {
      command.addAll(List.of("--no-daemon", "-Dorg.gradle.jvmargs=" + agent));
    }
    command.addAll(List.of(arguments));
    var log = work.resolve("gradle.log");
    var builder =
        new ProcessBuilder(command)
            .directory(project.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    builder.environment().putAll(env);
    int exit = builder.start().waitFor();
    return new Samples.Result(exit, Files.readString(log, StandardCharsets.UTF_8), "");
  }
}
