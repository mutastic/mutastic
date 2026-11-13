package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A project that doesn't build, or whose tests fail with no mutant in, is said in its own terms:
/// the compiler's error at the person's line, the failing test with what it said, and the build's
/// own command, which runs and fails the same way. A printed command that doesn't work is worse
/// than none, since people and agents both run it.
class BuildFailuresTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));

  @TempDir Path work;

  @Test
  void gradlesCompilerErrorIsQuotedAndItsCommandFailsTheSameWay() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("pricing"), work.resolve("pricing"));
    var source = project.resolve("src/main/java/com/example/pricing/Pricing.java");
    Files.writeString(source, Files.readString(source).replace("total / 10;", "total / 10"));

    var run = Samples.mutastic("run", project.toString());

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err())
        .contains(
            "mutastic: pricing doesn't build, before any mutant is in it.\nThe error is in "
                + source.toRealPath()
                + ", line 18: ';' expected.\nGradle's output is in ")
        .doesNotContain("* Try:", "--scan", "FAILURE:");
    assertThat(shell(lastLine(run.err()))).contains("Pricing.java:18: error: ';' expected");
  }

  @Test
  void gradlesFailingTestIsSaidAndItsCommandRunsItAlone() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("pricing"), work.resolve("pricing"));
    var test = project.resolve("src/test/java/com/example/pricing/PricingTest.java");
    Files.writeString(test, Files.readString(test).replace("assertEquals(25,", "assertEquals(24,"));

    var run = Samples.mutastic("run", project.toString());

    assertThat(run.exit()).isEqualTo(Main.TESTS_FAIL);
    assertThat(run.err())
        .contains(
            "mutastic: com.example.pricing.PricingTest#bigOrdersGetTenPercentOff fails without any"
                + " mutant, so mutastic can't judge one.\nIt failed: expected: <24> but was: <25>\n")
        .contains(" To run it alone:\n  ")
        .endsWith(" test --tests 'com.example.pricing.PricingTest.bigOrdersGetTenPercentOff'\n");
    assertThat(shell(lastLine(run.err())))
        .contains("PricingTest > bigOrdersGetTenPercentOff() FAILED", "1 test completed, 1 failed");
  }

  @Test
  void mavensFailingTestIsSaidAndItsCommandRunsItAlone() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("maven-modules"), work.resolve("maven-modules"));
    var test = project.resolve("core/src/test/java/com/example/core/FeesTest.java");
    Files.writeString(test, Files.readString(test).replace("assertEquals(3, ", "assertEquals(4, "));

    var run = Samples.mutastic("run", project.toString(), ":core");

    assertThat(run.exit()).isEqualTo(Main.TESTS_FAIL);
    assertThat(run.err())
        .contains(
            "mutastic: com.example.core.FeesTest#chargesPerTenStarted fails without any mutant,"
                + " so mutastic can't judge one.\nIt failed: expected: <4> but was: <3>\n")
        .endsWith(
            " test -pl core -am -Dsurefire.failIfNoSpecifiedTests=false"
                + " -Dtest='FeesTest#chargesPerTenStarted'\n");
    assertThat(shell(lastLine(run.err())))
        .contains("Tests run: 1, Failures: 1", "FeesTest.chargesPerTenStarted");
  }

  @Test
  void mavensCompilerErrorIsQuotedAndItsCommandFailsTheSameWay() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("maven-modules"), work.resolve("maven-modules"));
    var source = project.resolve("core/src/main/java/com/example/core/Fees.java");
    Files.writeString(source, Files.readString(source).replaceFirst(";\n", "\n"));

    var run = Samples.mutastic("run", project.toString(), ":core");

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err())
        .contains("mutastic: maven-modules/core doesn't build, before any mutant is in it.")
        .contains(source.toRealPath() + ", line 1: ")
        .endsWith(" test-compile -pl core -am\n");
    assertThat(shell(lastLine(run.err()))).contains("COMPILATION ERROR");
  }

  /// A file where mutastic keeps a directory is the disk's problem, said as the system says it,
  /// with a usage error's status: not an internal error naming a Java exception.
  @Test
  void aFileInTheWayIsSaidPlainly() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("pricing"), work.resolve("pricing"));
    Files.createDirectories(project.resolve("build"));
    Files.writeString(project.resolve("build/mutastic"), "");

    var run = Samples.mutastic("run", project.toString());

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err())
        .contains("mutastic: can't use ")
        .contains("build/mutastic: ")
        .contains("A file is where mutastic keeps a directory")
        .doesNotContain("internal error", "Exception");
  }

  /// The command a message ends in.
  private static String lastLine(String said) {
    var lines = said.strip().lines().toList();
    return lines.getLast().strip();
  }

  /// What `command` printed, run by a shell as a person would, from where the tests run.
  private static String shell(String command) throws IOException, InterruptedException {
    var process = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
    var said = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as(said).isNotZero();
    return said;
  }
}
