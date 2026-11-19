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

  /// An option mutastic has, typed for a command that doesn't take it, isn't dropped without a
  /// word: the command would run as if it weren't there.
  @Test
  void anOptionTheCommandDoesntTakeIsRefused() {
    var verify = Samples.mutastic("verify", "m855c8e3c", "samples/pricing", "--threshold=70");
    var run = Samples.mutastic(Map.of("MUTASTIC_FORMAT", "jsonl"), "run", "--top=3");

    assertThat(verify.exit()).isEqualTo(Main.USAGE);
    assertThat(verify.err()).isEqualTo("mutastic: verify doesn't take --threshold\n");
    assertThat(run.out()).contains("\"code\":\"option-not-taken\"");
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

  /// With no close name, the error ends in the one command that lists what there is: no usage line,
  /// which the Rust edition never had.
  @Test
  void withoutOneItEndsInTheHelpToRead() {
    assertThat(Samples.mutastic("run", "--fast").err())
        .isEqualTo(
            """
            mutastic: run has no option --fast. To see its options:
              mutastic run --help
            """);
    assertThat(Samples.mutastic("run", "-v").err())
        .isEqualTo(
            """
            mutastic: run has no option -v.
            mutastic's options are long ones, such as --format=full. To see them all:
              mutastic run --help
            """);
    assertThat(Samples.mutastic("frobnicate").err())
        .isEqualTo(
            """
            mutastic: mutastic has no command frobnicate. To see its commands:
              mutastic --help
            """);
    assertThat(Samples.mutastic("gate").err())
        .isEqualTo(
            """
            mutastic: gate needs what to do: open, check or close. To see what each does:
              mutastic --help
            """);
  }

  /// A missing value is said with the values it takes, if it takes only some, and the command with
  /// one to fill in.
  @Test
  void aMissingValueEndsInTheCommandToFillIn() {
    assertThat(Samples.mutastic("next", "--format").err())
        .isEqualTo(
            """
            mutastic: --format needs a value, one of full, brief, concise, grouped, agent or jsonl.
              mutastic next --format=<format>
            """);
    assertThat(Samples.mutastic("run", "--threshold").err())
        .isEqualTo(
            """
            mutastic: --threshold needs a value.
              mutastic run --threshold=<n>
            """);
  }

  /// An ignore with no reason ends in its command with one to fill in, its directory and options
  /// kept.
  @Test
  void anIgnoreWithoutAReasonEndsInOneToFillIn() {
    assertThat(Samples.mutastic("ignore", "m855c8e3c", "samples/pricing", "--propose").err())
        .isEqualTo(
            """
            mutastic: ignore needs a reason, for whoever reviews it:
              mutastic ignore m855c8e3c samples/pricing --reason="<why>" --propose
            """);
    assertThat(Samples.mutastic("ignore", "m855c8e3c", "--reason").err())
        .startsWith("mutastic: ignore needs a reason, for whoever reviews it:\n");
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
            "mutastic: m855c83ec isn't a mutant of pricing. There's one called m855c8e3c.\n"
                + "  mutastic verify m855c8e3c "
                + project
                + "\n");
  }
}
