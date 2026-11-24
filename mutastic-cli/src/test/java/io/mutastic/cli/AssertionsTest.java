package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Assertions, on `samples/maven-assertions`: its tests check an `assert`, so they pass only with
/// assertions on, which Surefire turns on unless `enableAssertions` is false.
class AssertionsTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));

  @TempDir Path work;

  @Test
  void mavenRunsTheTestsWithAssertionsOnAsSurefireDoes() throws Exception {
    var project =
        Samples.copy(SAMPLES.resolve("maven-assertions"), work.resolve("maven-assertions"));

    var run = Samples.mutastic("run", project.toString(), "--format=agent");

    assertThat(run.exit()).as(run.out()).isZero();
    assertThat(run.out()).contains("PASS").doesNotContain("fails without any mutant");
  }

  @Test
  void aBuildThatTurnsThemOffKeepsThemOff() throws Exception {
    var project =
        Samples.copy(SAMPLES.resolve("maven-assertions"), work.resolve("maven-assertions"));
    var pom = project.resolve("pom.xml");
    Files.writeString(
        pom,
        Files.readString(pom)
            .replace(
                "<version>3.5.4</version>",
                "<version>3.5.4</version>\n"
                    + "        <configuration><enableAssertions>false</enableAssertions>"
                    + "</configuration>"));

    var run = Samples.mutastic("run", project.toString(), "--format=concise");

    // Surefire fails the test the same way: it's the build's choice, and mutastic keeps it.
    assertThat(run.exit()).as(run.out()).isEqualTo(3);
    assertThat(run.out() + run.err())
        .contains("DepositTest#isNeverNegative fails without any mutant");
  }
}
