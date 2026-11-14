package io.mutastic.cli;

import static io.mutastic.cli.Samples.mutastic;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `mutants` on the pricing sample: its 11 mutants a line each, as a run finds them, with no test
/// run and nothing written but the build.
class MutantsCommandTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");

  @TempDir Path work;

  private Path project;

  @BeforeEach
  void aCopyOfPricing() throws Exception {
    project = Samples.copy(SAMPLE, work.resolve("pricing"));
  }

  @Test
  void eachMutantIsALineThenTheCount() {
    var listed = mutastic("mutants", project.toString());

    assertThat(listed.exit()).as(listed.err()).isEqualTo(Main.PASSED);
    var lines = listed.out().lines().toList();
    assertThat(lines).hasSize(12).last().isEqualTo("11 mutants");
    assertThat(lines)
        .contains(
            "m855c8e3c src/main/java/com/example/pricing/Pricing.java:17:15 mutastic/boundary >="
                + " becomes > in Pricing.discount()");
    assertThat(lines.subList(0, 11))
        .allMatch(line -> line.matches("m[0-9a-f]{8} \\S+\\.java:\\d+:\\d+ \\S+ .+ in .+"));
    assertThat(project.resolve("build/mutastic/mutation.json")).doesNotExist();
  }

  @Test
  void moreOperatorsListMoreMutants() {
    var stronger = mutastic("mutants", project.toString(), "--operators=stronger");

    assertThat(stronger.exit()).as(stronger.err()).isEqualTo(Main.PASSED);
    assertThat(stronger.out().lines().count()).isGreaterThan(12);
    assertThat(stronger.out()).endsWith(" mutants\n");
  }

  @Test
  void anOperatorNothingHasIsAUsageError() {
    var unknown = mutastic("mutants", project.toString(), "--operators=nothing");

    assertThat(unknown.exit()).isEqualTo(Main.USAGE);
    assertThat(unknown.out()).isEmpty();
  }

  @Test
  void aWordMoreThanTheDirectoryIsRefused() {
    var extra = mutastic("mutants", project.toString(), "elsewhere");

    assertThat(extra.exit()).isEqualTo(Main.USAGE);
    assertThat(extra.err()).contains("mutants doesn't take elsewhere");
  }
}
