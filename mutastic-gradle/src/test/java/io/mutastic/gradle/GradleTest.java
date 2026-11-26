package io.mutastic.gradle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mutastic.api.BuildArguments;
import io.mutastic.api.BuildErrors;
import io.mutastic.api.BuildTool;
import io.mutastic.api.BuildTool.SetupFailed;
import io.mutastic.api.Wrappers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class GradleTest {
  private final Gradle gradle = new Gradle();

  @Test
  void eachModuleKeepsItsOwnWorkDirectory() {
    var project = Path.of("/code/app");

    assertThat(gradle.workDirectory(project, ":")).isEqualTo(Path.of("/code/app/build/mutastic"));
    assertThat(gradle.workDirectory(project, ":data"))
        .isEqualTo(Path.of("/code/app/build/mutastic/data"));
    assertThat(gradle.workDirectory(project, ":services:api"))
        .isEqualTo(Path.of("/code/app/build/mutastic/services/api"));
  }

  /// The build's log has the tasks it ran, for progress to show; a failure says what `-q` would
  /// have said.
  @Test
  void aFailureSaysWhatGradleSaidBeyondItsProgress() {
    var log =
        """
        Starting a Gradle Daemon (subsequent builds will be faster)
        > Task :compileJava FAILED
        Pricing.java:3: error: ';' expected

        FAILURE: Build failed with an exception.

        BUILD FAILED in 2s
        1 actionable task: 1 executed
        """;

    assertThat(GradleSetup.said(log))
        .isEqualTo(
            "Pricing.java:3: error: ';' expected\n\nFAILURE: Build failed with an exception.");
  }

  /// What reading a module builds, as a person would run it.
  @Test
  void buildingNamesTheSuitesClasses() {
    var work = Path.of("/code/app/build/mutastic");

    assertThat(
            gradle
                .building(new BuildTool.Request(Path.of("/code/app"), ":", work, ".", "test"))
                .map(building -> List.of(building.task(), building.log())))
        .contains(List.of("gradle testClasses", work.resolve("gradle.log")));
    assertThat(
            gradle
                .building(
                    new BuildTool.Request(
                        Path.of("/code/app"), ":core", work, ".", "integrationTest"))
                .map(BuildTool.Building::task))
        .contains("gradle :core:integrationTestClasses");
  }

  @Test
  void aBuildIsADirectoryWithGradlesBuildFiles(@TempDir Path directory) throws Exception {
    assertThat(gradle.recognises(directory)).isFalse();

    Files.writeString(directory.resolve("settings.gradle.kts"), "");

    assertThat(gradle.recognises(directory)).isTrue();
  }

  /// The build runs as it does for the person: with its wrapper when it has one.
  @Test
  void aBuildWithAWrapperRunsWithIt(@TempDir Path directory) throws Exception {
    Files.writeString(directory.resolve("settings.gradle.kts"), "");
    var wrapper = executable(directory.resolve(GRADLEW));

    assertThat(GradleSetup.gradle(directory, onPath(directory)).command())
        .containsExactly(wrapper.toString());
  }

  @Test
  void aBuildWithoutAWrapperRunsWithTheGradleOnThePath(@TempDir Path directory) throws Exception {
    Files.writeString(directory.resolve("settings.gradle.kts"), "");

    var tool = GradleSetup.gradle(directory, onPath(directory));

    assertThat(tool.command()).containsExactly(Wrappers.WINDOWS ? "gradle.bat" : "gradle");
    assertThat(tool.onPath()).isTrue();
  }

  @Test
  void withNeitherTheSetupSaysSo(@TempDir Path directory) throws Exception {
    Files.writeString(directory.resolve("settings.gradle.kts"), "");

    assertThatThrownBy(() -> GradleSetup.gradle(directory, ""))
        .isInstanceOf(SetupFailed.class)
        .hasMessage(
            directory + " has no Gradle wrapper (gradlew), and there's no `gradle` to run instead");
  }

  /// A module's directory runs the wrapper at the top of its build, where its settings are; outside
  /// a repository, one above that belongs to something else.
  @Test
  void aModuleDirectoryRunsItsBuildsWrapper(@TempDir Path directory) throws Exception {
    executable(directory.resolve(GRADLEW));
    var root = Files.createDirectories(directory.resolve("app"));
    Files.writeString(root.resolve("settings.gradle.kts"), "");
    var wrapper = executable(root.resolve(GRADLEW));
    var module = Files.createDirectories(root.resolve("libs/core"));
    Files.writeString(module.resolve("build.gradle.kts"), "");

    assertThat(GradleSetup.gradle(module, onPath(directory)).command())
        .containsExactly(wrapper.toString());

    Files.delete(wrapper);

    assertThat(GradleSetup.gradle(module, onPath(directory)).onPath()).isTrue();
  }

  /// A module with settings of its own is a build nested in the repository's: the repository's
  /// wrapper runs it, as `../gradlew` would, and not a Gradle on the PATH that may be too old.
  @Test
  void aNestedBuildRunsTheWrapperAtTheTopOfItsRepository(@TempDir Path directory) throws Exception {
    Files.createDirectories(directory.resolve(".git"));
    Files.writeString(directory.resolve("settings.gradle"), "");
    var wrapper = executable(directory.resolve(GRADLEW));
    var nested = Files.createDirectories(directory.resolve("standalone-event-gateway"));
    Files.writeString(nested.resolve("settings.gradle"), "");
    Files.writeString(nested.resolve("build.gradle"), "");

    assertThat(GradleSetup.gradle(nested, onPath(directory)).command())
        .containsExactly(wrapper.toString());
    assertThat(GradleSetup.task(nested, ":", "mutasticSetup")).isEqualTo(":mutasticSetup");

    var own = executable(nested.resolve(GRADLEW));

    assertThat(GradleSetup.gradle(nested, onPath(directory)).command())
        .containsExactly(own.toString());
  }

  /// No further up than the repository: a wrapper above it belongs to something else.
  @Test
  void aWrapperAboveTheRepositoryIsntTheBuilds(@TempDir Path directory) throws Exception {
    executable(directory.resolve(GRADLEW));
    var repository = Files.createDirectories(directory.resolve("repository"));
    Files.createDirectories(repository.resolve(".git"));
    var nested = Files.createDirectories(repository.resolve("app"));
    Files.writeString(nested.resolve("settings.gradle.kts"), "");

    assertThat(GradleSetup.gradle(nested, onPath(directory)).onPath()).isTrue();
  }

  /// A checkout can lose a wrapper's executable bit: it runs with `sh`, and the commands mutastic
  /// prints say so, so they run as they're copied.
  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows runs gradlew.bat as it is")
  void aWrapperThatIsntExecutableRunsWithSh(@TempDir Path directory) throws Exception {
    Files.writeString(directory.resolve("settings.gradle.kts"), "");
    var wrapper = Files.writeString(directory.resolve("gradlew"), "#!/bin/sh\n");

    assertThat(GradleSetup.gradle(directory, onPath(directory)).command())
        .containsExactly("sh", wrapper.toString());
    assertThat(
            GradleSetup.testCommand(
                new BuildTool.Request(directory, ":", directory.resolve("build"), ".", "test"),
                "com.example.PricingTest#bigOrders",
                BuildArguments.NONE))
        .startsWith("sh ")
        .endsWith(
            "gradlew -p %s test --tests 'com.example.PricingTest.bigOrders'"
                .formatted(directory.toRealPath()));
  }

  /// A build in a directory with a space: the commands mutastic prints run as they're copied, a
  /// shell reading the wrapper's path and the build's directory as one word each, run with the
  /// wrapper, with `sh` or with the Gradle on the PATH.
  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "a POSIX shell reads the command")
  void aBuildInADirectoryWithASpaceGetsCommandsThatRunAsTheyreCopied(@TempDir Path work)
      throws Exception {
    var directory = Files.createDirectories(work.resolve("My Projects/app"));
    Files.writeString(directory.resolve("settings.gradle.kts"), "");
    var wrapper = executable(directory.resolve("gradlew"));
    var request = new BuildTool.Request(directory, ":", directory.resolve("build"), ".", "test");
    var real = directory.toRealPath().toString();
    var filter = List.of("-p", real, "test", "--tests", "com.example.PricingTest.bigOrders");

    var command =
        GradleSetup.testCommand(request, "com.example.PricingTest#bigOrders", BuildArguments.NONE);

    assertThat(command).startsWith("'" + wrapper.toRealPath() + "' -p '" + real + "' test");
    assertThat(argv(command)).isEqualTo(concat(List.of(wrapper.toRealPath().toString()), filter));

    assertThat(wrapper.toFile().setExecutable(false)).isTrue();

    assertThat(
            argv(
                GradleSetup.testCommand(
                    request, "com.example.PricingTest#bigOrders", BuildArguments.NONE)))
        .isEqualTo(concat(List.of("sh", wrapper.toRealPath().toString()), filter));

    Files.delete(wrapper);
    var path = onPath(work);
    var onPath =
        BuildErrors.command(
            GradleSetup.gradle(directory, path).command(),
            directory,
            "-p",
            List.of("test", "--tests", "'com.example.PricingTest.bigOrders'"));

    assertThat(argv(onPath)).isEqualTo(concat(List.of("gradle"), filter));
  }

  private static List<String> concat(List<String> first, List<String> rest) {
    return java.util.stream.Stream.concat(first.stream(), rest.stream()).toList();
  }

  /// The words `sh` reads in `command`.
  private static List<String> argv(String command) throws Exception {
    var process = new ProcessBuilder("sh", "-c", "printf '%s\\n' " + command).start();
    var words = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).isZero();
    return words.lines().toList();
  }

  /// With no wrapper, mutastic says which Gradle runs before it builds, and its version when its
  /// distribution's jars say: a build made for a newer Gradle fails while it's read.
  @Test
  void theGradleOnThePathIsSaidWithItsVersion(@TempDir Path directory) throws Exception {
    Files.writeString(directory.resolve("settings.gradle.kts"), "");
    var home = Files.createDirectories(directory.resolve("gradle-8.2.1"));
    var gradle = executable(Files.createDirectories(home.resolve("bin")).resolve(PROGRAM));
    Files.writeString(
        Files.createDirectories(home.resolve("lib")).resolve("gradle-launcher-8.2.1.jar"), "");

    var tool = GradleSetup.gradle(directory, home.resolve("bin").toString());

    assertThat(GradleSetup.onPath(tool))
        .isEqualTo(
            "no Gradle wrapper (gradlew) here or above: running Gradle 8.2.1 from the PATH, "
                + gradle);
    assertThat(GradleSetup.likely(directory, home.resolve("bin").toString()))
        .contains(
            ("It ran with Gradle 8.2.1 from the PATH, %s, as the build has no wrapper (gradlew)"
                    + " here or above. A build made for another Gradle fails this way: add the"
                    + " wrapper it expects, or put the Gradle it needs first on the PATH.")
                .formatted(gradle));
    assertThat(GradleSetup.likely(directory, onPath(directory))).isPresent();

    executable(directory.resolve(GRADLEW));

    assertThat(GradleSetup.likely(directory, onPath(directory))).isEmpty();
  }

  /// A Gradle launched from elsewhere, a shim, isn't run to ask its version: that takes seconds.
  @Test
  void aGradleWhoseVersionIsntBesideItIsSaidWithout(@TempDir Path directory) throws Exception {
    Files.writeString(directory.resolve("settings.gradle.kts"), "");

    var tool = GradleSetup.gradle(directory, onPath(directory));

    assertThat(GradleSetup.onPath(tool))
        .isEqualTo(
            "no Gradle wrapper (gradlew) here or above: running Gradle from the PATH, "
                + tool.file());
  }

  /// Run from a module's directory, Gradle runs the whole build: a task is named from its top.
  @Test
  void aModuleDirectorysTasksAreNamedFromTheTopOfItsBuild(@TempDir Path directory)
      throws Exception {
    Files.writeString(directory.resolve("settings.gradle.kts"), "");
    var module = Files.createDirectories(directory.resolve("libs/core"));
    Files.writeString(module.resolve("build.gradle.kts"), "");

    assertThat(GradleSetup.task(directory, ":", "mutasticSetup")).isEqualTo(":mutasticSetup");
    assertThat(GradleSetup.task(directory, ":data", "mutasticSetup"))
        .isEqualTo(":data:mutasticSetup");
    assertThat(GradleSetup.task(module, ":", "mutasticSetup"))
        .isEqualTo(":libs:core:mutasticSetup");
  }

  private static final String GRADLEW = Wrappers.WINDOWS ? "gradlew.bat" : "gradlew";

  private static final String PROGRAM = Wrappers.WINDOWS ? "gradle.bat" : "gradle";

  /// A PATH with a `gradle` on it, in `directory/bin`.
  private static String onPath(Path directory) throws Exception {
    executable(Files.createDirectories(directory.resolve("bin")).resolve(PROGRAM));
    return directory.resolve("bin").toString();
  }

  private static Path executable(Path file) throws Exception {
    if (!Files.exists(file)) {
      Files.writeString(file, "#!/bin/sh\n");
    }
    assertThat(file.toFile().setExecutable(true)).isTrue();
    return file;
  }
}
