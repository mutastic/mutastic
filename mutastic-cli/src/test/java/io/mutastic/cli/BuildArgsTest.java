package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mutastic.api.BuildArguments;
import io.mutastic.api.BuildTool;
import io.mutastic.report.Invocation;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildArgsTest {
  @TempDir Path project;

  @BeforeEach
  void aGradleBuild() throws Exception {
    Files.writeString(project.resolve("settings.gradle.kts"), "");
  }

  private BuildArguments of(Map<String, String> env, String... args) {
    BuildTool tool = BuildTools.of(project);
    return BuildArgs.of(tool, Main.Command.parse(args), project, env);
  }

  /// Nothing after the `--` is mutastic's own, whatever it looks like.
  @Test
  void whatFollowsTheDashesIsTheBuildTools() {
    var command = Main.Command.parse(new String[] {"run", "app", "--", "--since", "--help", "x"});
    assertThat(command.directory()).isEqualTo(Path.of("app"));
    assertThat(command.since()).isEmpty();
    assertThat(command.buildArguments()).contains(List.of("--since", "--help", "x"));
    for (var each : Main.Subcommand.values()) {
      var words = each.word() + (each.takesMutantId ? " m855c8e3c" : "");
      var reason = each == Main.Subcommand.IGNORE ? " --reason=why" : "";
      var parsed = Main.Command.parse((words + reason + " -- -Pci").split(" "));
      assertThat(parsed.buildArguments()).as(words).contains(List.of("-Pci"));
    }
  }

  /// The first given is used whole: the command line, the variable, the file.
  @Test
  void theFirstGivenIsUsedWhole() throws Exception {
    Files.writeString(
        project.resolve("mutastic.yaml"), "build:\n  args:\n    - -Pfile\n    - --offline\n");
    var env = Map.of(BuildArgs.VARIABLE, "-Pvariable");

    assertThat(of(Map.of(), "run")).isEqualTo(BuildArguments.quiet(List.of("-Pfile", "--offline")));
    assertThat(of(env, "run")).isEqualTo(BuildArguments.quiet(List.of("-Pvariable")));
    assertThat(of(Map.of(BuildArgs.VARIABLE, " "), "run").words()).hasSize(2);
    assertThat(of(env, "run", "--", "-Ptyped")).isEqualTo(BuildArguments.typed(List.of("-Ptyped")));
    assertThat(of(env, "run", "--").words()).isEmpty();
  }

  @Test
  void aModulesOwnFileIsFoundBeforeTheBuildIsRead() throws Exception {
    Files.createDirectories(project.resolve("libs/core"));
    Files.writeString(project.resolve("mutastic.yaml"), "build:\n  args:\n    - -Ptop\n");
    Files.writeString(
        project.resolve("libs/core/mutastic.yaml"), "build:\n  args:\n    - -Pcore\n");

    assertThat(of(Map.of(), "run", ".", ":libs:core").words()).containsExactly("-Pcore");
    assertThat(of(Map.of(), "run", ".", ":elsewhere").words()).containsExactly("-Ptop");
  }

  /// Refused by where it was given, with no value in the words.
  @Test
  void aRefusalSaysWhereItWasGiven() throws Exception {
    assertThatThrownBy(() -> of(Map.of(), "run", "--", "--tests=Secret"))
        .isInstanceOf(Main.UsageException.class)
        .hasMessage("--tests can't be passed to Gradle: mutastic chooses the tests it runs");
    assertThatThrownBy(() -> of(Map.of(BuildArgs.VARIABLE, "-t"), "run"))
        .isInstanceOf(Main.ConfigException.class)
        .hasMessage("MUTASTIC_BUILD_ARGS: -t can't be passed to Gradle: the build would never end");
    assertThatThrownBy(() -> of(Map.of(BuildArgs.VARIABLE, "'-P"), "run"))
        .hasMessage("MUTASTIC_BUILD_ARGS: a ' isn't closed");
    Files.writeString(project.resolve("mutastic.yaml"), "build:\n  args:\n    - --dry-run\n");
    assertThatThrownBy(() -> of(Map.of(), "run"))
        .hasMessageStartingWith("mutastic.yaml line 3: --dry-run can't be passed to Gradle: ");
  }

  /// Each command offered, and a mistyped one corrected, ends in what was typed.
  @Test
  void offeredCommandsKeepWhatWasTyped() {
    var typed = Optional.of(List.of("-Pa=b c", "--offline"));
    assertThat(Invocation.HERE.building(typed).command(List.of("verify", "m855c8e3c")))
        .isEqualTo("mutastic verify m855c8e3c -- '-Pa=b c' --offline");
    var out = new ByteArrayOutputStream();
    var err = new PrintStream(out, true, StandardCharsets.UTF_8);
    Main.run(
        new String[] {"run", "--treshold=70", "--", "-Pa=b c", "--help"},
        err,
        err,
        new Terminal(false, Map.of()));
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains("  mutastic run --threshold=70 -- '-Pa=b c' --help\n");
  }

  /// Once, whatever else the line has in it.
  @Test
  void anOfferedCommandEndsInWhatWasTypedOnce() {
    var end = " -- -Pa";
    assertThat(Failure.withEnding("x\n  mutastic run 'my app' :core\n", end))
        .isEqualTo("x\n  mutastic run 'my app' :core -- -Pa\n");
    assertThat(Failure.withEnding("  mutastic run 'a -- b'", end))
        .isEqualTo("  mutastic run 'a -- b' -- -Pa");
    assertThat(Failure.withEnding("  mutastic run x -- -Pa", end))
        .isEqualTo("  mutastic run x -- -Pa");
    assertThat(Failure.withEnding("  mutastic run x --", " --")).isEqualTo("  mutastic run x --");
    assertThat(Failure.withEnding("  mutastic run --help\n  ./gradlew x", end))
        .isEqualTo("  mutastic run --help\n  ./gradlew x");
  }

  /// One set of arguments, one key; another, another; none, none. The key file is its owner's.
  @Test
  void theArgumentsStandInARecordUnderAKeyOfTheWorkDirectorys() throws Exception {
    var work = project.resolve("work");
    var first = BuildArgsKey.of(work, List.of("-Ppin=1234"));
    assertThat(BuildArgsKey.of(work, List.of("-Ppin=1234"))).isEqualTo(first);
    assertThat(BuildArgsKey.of(work, List.of("-Ppin=1235"))).isNotEqualTo(first);
    assertThat(BuildArgsKey.of(work, List.of("a", "b")))
        .isNotEqualTo(BuildArgsKey.of(work, List.of("b", "a")))
        .isNotEqualTo(BuildArgsKey.of(work, List.of("ab")));
    assertThat(BuildArgsKey.of(work, List.of())).isEmpty();
    assertThat(BuildArgsKey.of(project.resolve("other"), List.of("-Ppin=1234")))
        .isNotEqualTo(first);
    if (Files.getFileStore(work).supportsFileAttributeView("posix")) {
      assertThat(
              java.nio.file.attribute.PosixFilePermissions.toString(
                  Files.getPosixFilePermissions(work.resolve("build-args.key"))))
          .isEqualTo("rw-------");
    }
  }
}
