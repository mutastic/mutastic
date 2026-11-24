package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A parameterized test that takes a class of its own test sources, beside the code it tests. In
/// the `parameter-types` sample, `PostageTest#costsByWeight` takes a `Parcel`, a package-private
/// record of the tests. A test in a package the code also uses runs as a fresh copy each run, and
/// so does every test class of that package it refers to, `Parcel` among them: the test is selected
/// on the copy by the copy's parameter type. Selected by the type the build gave it, JUnit found no
/// such method, every mutant counted as caught, and running the test on its own after the mutants
/// found it "failing on its own", so its catches were dropped and the run failed.
class ParameterTypesTest {
  private static final Path SAMPLE =
      Path.of(System.getProperty("mutastic.samples"), "parameter-types");

  @TempDir Path work;

  @Test
  void aTestTakingAClassOfTheTestsCatchesItsMutantsAndPasses() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("parameter-types"));

    var run = Samples.mutastic("run", project.toString(), "--workers=1", "--format=agent");

    assertThat(run.out().lines().findFirst())
        .hasValue(
            "mutastic: PASS · score 100.0% (threshold 80) · 0 missed, 0 not covered, 4 caught of 4"
                + " mutants");
    assertThat(run.out()).doesNotContain("flaky");
    assertThat(run.exit()).isZero();
  }
}
