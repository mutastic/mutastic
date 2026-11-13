package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// What a mistake on the command line prints: what's wrong, then either the command corrected, when
/// a close name says what was meant, or the one line of usage of the command typed. Nothing is run
/// in its place. A mistake in a file or the environment isn't the command line's, and gets no
/// usage.
class UsageErrorsTest {
  @TempDir Path work;

  @Test
  void aCloseOptionIsSaidWithTheCommandCorrected() {
    var run = Samples.mutastic("run", "samples/pricing", "--formt=agent", "--threshold=70");

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err())
        .isEqualTo(
            """
            mutastic: run has no option --formt. There's one called --format.
              mutastic run samples/pricing --format=agent --threshold=70
            """);
  }

  /// A value given after a space is corrected to the `=` form the docs and help write.
  @Test
  void aCloseValueIsCorrectedWithAnEquals() {
    assertThat(Samples.mutastic("next", "--format", "agnt").err())
        .isEqualTo(
            """
            mutastic: --format has no value agnt. There's one called agent.
              mutastic next --format=agent
            """);
    assertThat(Samples.mutastic("run", "--report=josn:out/m.json").err())
        .endsWith("  mutastic run --report=json:out/m.json\n");
  }

  @Test
  void aCloseCommandIsSaidNotRun() {
    assertThat(Samples.mutastic("verfiy", "m855c8e3c").err())
        .isEqualTo(
            """
            mutastic: mutastic has no command verfiy. There's one called verify.
              mutastic verify m855c8e3c
            """);
    assertThat(Samples.mutastic("gate", "opne").err())
        .isEqualTo(
            """
            mutastic: gate has no command opne. There's one called open.
              mutastic gate open
            """);
  }

  /// With no close name, one line of the command's usage follows, not every command's.
  @Test
  void withoutOneTheUsageIsOneLine() {
    assertThat(Samples.mutastic("run", "--fast").err())
        .isEqualTo(
            """
            mutastic: run has no option --fast
            usage: mutastic run [DIR] [:MODULE] [OPTIONS]  (mutastic run --help says more)
            """);
    assertThat(Samples.mutastic("frobnicate").err())
        .isEqualTo(
            """
            mutastic: mutastic has no command frobnicate; its commands are run, verify, explain,\
             ignore, next, baseline and gate
            usage: mutastic COMMAND [DIR] [:MODULE] [OPTIONS]  (mutastic --help says more)
            """);
  }

  /// A word quoted as typed is quoted in the command offered, so it can be pasted back.
  @Test
  void theCorrectedCommandKeepsQuotedWords() {
    assertThat(Samples.mutastic("ignore", "m855c8e3c", "--reasn=only logs").err())
        .endsWith("  mutastic ignore m855c8e3c '--reason=only logs'\n");
    assertThat(Correction.quoted("it's")).isEqualTo("'it'\\''s'");
    assertThat(Correction.of("m855c8e3d", "m855c8e3c").in(List.of("verify", "--only=m1,m855c8e3d")))
        .hasValue("verify --only=m1,m855c8e3c");
    assertThat(Correction.of("x", "y").in(List.of("run"))).isEmpty();
  }

  /// A build plugin passes its own arguments, which the person never typed: the close name is said,
  /// but no `mutastic` command to paste.
  @Test
  void underABuildPluginOnlyTheNameIsSaid() {
    assertThat(Samples.mutastic("run", "--setup=setup.json", "--formt=agent").err())
        .startsWith("mutastic: run has no option --formt. There's one called --format.\n")
        .doesNotContain("  mutastic run");
  }

  @Test
  void aMistakeInAFileOrTheEnvironmentHasNoUsage() throws Exception {
    var project =
        Samples.copy(
            Path.of(System.getProperty("mutastic.samples"), "pricing"), work.resolve("pricing"));
    Files.writeString(project.resolve(Configuration.FILE), "oprators:\n  - stronger\n");

    var config = Samples.mutastic("run", project.toString());
    var environment =
        Samples.mutastic(Map.of("MUTASTIC_FORMAT", "agnt"), "run", project.toString());

    assertThat(config.exit()).isEqualTo(Main.USAGE);
    assertThat(config.err())
        .endsWith(
            "mutastic: mutastic.yaml line 1: oprators: isn't a section. There's one called"
                + " operators:.\n")
        .doesNotContain("usage:");
    assertThat(environment.err()).doesNotContain("usage:");
  }

  /// An id one slip from one of the project's is said, with the command as it should have been.
  @Test
  void anIdOneSlipAwayIsSaidNotRun() throws Exception {
    var project =
        Samples.copy(
            Path.of(System.getProperty("mutastic.samples"), "pricing"), work.resolve("pricing"));

    var run = Samples.mutastic("verify", "m855c83ec", project.toString());

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err())
        .endsWith(
            "mutastic: no mutant m855c83ec in pricing. There's one called m855c8e3c.\n"
                + "  mutastic verify m855c8e3c "
                + project
                + "\n");
  }
}
