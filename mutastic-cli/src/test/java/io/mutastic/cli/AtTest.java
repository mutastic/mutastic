package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/// `--at=PATH:LINE[-LINE]`, as typed: a file and a line or lines, split from the right, several
/// split by commas or given again; and the options it can't be given with.
class AtTest {
  @Test
  void takesAFileAndALine() {
    assertThat(At.parse("src/main/java/com/example/Pricing.java:17"))
        .containsExactly(new At("src/main/java/com/example/Pricing.java", 17, 17));
  }

  @Test
  void takesLinesFromOneToAnother() {
    assertThat(At.parse("Pricing.java:17-20")).containsExactly(new At("Pricing.java", 17, 20));
    assertThat(At.parse("Pricing.java:17-17")).containsExactly(new At("Pricing.java", 17, 17));
  }

  @Test
  void takesSeveralSplitByCommas() {
    assertThat(At.parse("Pricing.java:17, Tier.java:3-5"))
        .containsExactly(new At("Pricing.java", 17, 17), new At("Tier.java", 3, 5));
  }

  /// The lines are split from the right, so a drive's colon stays in the path.
  @Test
  void keepsAWindowsDriveInThePath() {
    assertThat(At.parse("C:\\work\\pricing\\Pricing.java:17-20"))
        .containsExactly(new At("C:\\work\\pricing\\Pricing.java", 17, 20));
  }

  @Test
  void writesItBackAsTyped() {
    assertThat(new At("Pricing.java", 17, 17).typed("src/Pricing.java"))
        .isEqualTo("src/Pricing.java:17");
    assertThat(new At("Pricing.java", 17, 20).typed("src/Pricing.java"))
        .isEqualTo("src/Pricing.java:17-20");
  }

  @Test
  void refusesAFileWithoutALine() {
    for (var typed :
        List.of("Pricing.java", "Pricing.java:", ":17", "Pricing.java:x", "C:\\a.java")) {
      assertThatThrownBy(() -> At.parse(typed))
          .as(typed)
          .isInstanceOf(Main.UsageException.class)
          .hasMessageContaining("--at takes a source file and a line");
    }
  }

  @Test
  void refusesALineBeforeTheFirst() {
    assertThatThrownBy(() -> At.parse("Pricing.java:0"))
        .hasMessage(
            "--at=Pricing.java:0: a file's lines are numbered from 1, as an editor shows them");
    assertThatThrownBy(() -> At.parse("Pricing.java:99999999999"))
        .hasMessageContaining("numbered from 1");
  }

  @Test
  void refusesLinesThatEndBeforeTheyStart() {
    assertThatThrownBy(() -> At.parse("Pricing.java:20-17"))
        .hasMessage("--at=Pricing.java:20-17 ends before it starts: --at=Pricing.java:17-20");
  }

  /// `mutants` prints a place to the column: `--at` takes its line.
  @Test
  void refusesAColumnWithTheLineItsOn() {
    assertThatThrownBy(() -> At.parse("src/Pricing.java:17:15"))
        .hasMessage("--at takes a line, not a column: --at=src/Pricing.java:17");
  }

  @Test
  void refusesAnEmptyOneBetweenCommas() {
    assertThatThrownBy(() -> At.parse("A.java:1,,B.java:2"))
        .hasMessageStartingWith("--at=A.java:1,,B.java:2 has an empty one between its commas.");
    assertThatThrownBy(() -> At.parse("")).hasMessageStartingWith("--at needs a value.");
  }

  @Test
  void isRepeatedOrSplitByCommasOnTheCommandLine() {
    var given = parse("run", "--at=A.java:1", "--at", "B.java:2-3");
    var split = parse("run", "--at=A.java:1,B.java:2-3");

    assertThat(given.at()).containsExactly(new At("A.java", 1, 1), new At("B.java", 2, 3));
    assertThat(split).isEqualTo(given);
    assertThat(given.partial()).isTrue();
  }

  /// A build plugin's property, `-Dmutastic.at=…` or `-Pmutastic.at=…`, is the option.
  @Test
  void isABuildPluginsProperty() {
    assertThat(parse("run", "-Dmutastic.at=A.java:1,B.java:2-3"))
        .isEqualTo(parse("run", "--at=A.java:1", "--at=B.java:2-3"));
    assertThat(parse("mutants", "-Pmutastic.at=A.java:1"))
        .isEqualTo(parse("mutants", "--at=A.java:1"));
  }

  @Test
  void runAndMutantsTakeItAndNoOtherCommandDoes() {
    assertThat(parse("mutants", "--at=A.java:1").at()).containsExactly(new At("A.java", 1, 1));
    assertThatThrownBy(() -> parse("verify", "m855c8e3c", "--at=A.java:1"))
        .isInstanceOf(Main.UsageException.class)
        .hasMessage("verify doesn't take --at");
  }

  @Test
  void isNotGivenWithOnly() {
    assertThatThrownBy(() -> parse("run", "--at=A.java:1", "--only=m855c8e3c"))
        .hasMessage(
            "--only and --at both choose the mutants that run: give one, the ids or the lines");
  }

  @Test
  void isNotGivenWithFailOnNew() {
    assertThatThrownBy(() -> parse("run", "--at=A.java:1", "--fail-on-new"))
        .hasMessage(
            "--fail-on-new judges a run of the module on its new misses: not with --at, whose"
                + " mutants each pass when they're caught");
  }

  @Test
  void isNotGivenWithFromShards() {
    assertThatThrownBy(() -> parse("run", "--at=A.java:1", "--from-shards=shares"))
        .isInstanceOfSatisfying(
            Main.UsageException.class, refused -> assertThat(refused.code()).isEqualTo("shards"))
        .hasMessageEndingWith("not with --at, which the shares run with");
  }

  private static Main.Command parse(String... args) {
    return Main.Command.parse(args);
  }
}
