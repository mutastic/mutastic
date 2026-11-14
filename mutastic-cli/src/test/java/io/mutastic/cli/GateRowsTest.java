package io.mutastic.cli;

import static io.mutastic.cli.Samples.mutastic;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The gate counts a parameterized test's rows, not its method, on `samples/parameterized-kotlin`:
/// a row a static source holds from its annotations, at once; a row code makes, a test a condition
/// turns off and a case an assumption aborts from the run `gate open` starts in the background.
/// Taking any of them away, or turning them off, holds the task.
class GateRowsTest {
  private static final Path TEST = Path.of("src/test/kotlin/com/example/calc/CalculatorTest.kt");

  @TempDir Path work;

  private Path project;

  @BeforeEach
  void aGatedTask() throws Exception {
    project =
        Samples.copy(
            Path.of(System.getProperty("mutastic.samples"), "parameterized-kotlin"),
            work.resolve("parameterized-kotlin"));
    Files.writeString(project.resolve(".gitignore"), "build/\n.gradle/\n.kotlin/\n");
    git("init", "-q", "-b", "main");
    git("add", "-A");
    git(
        "-c",
        "user.name=t",
        "-c",
        "user.email=t@t",
        "-c",
        "commit.gpgsign=false",
        "commit",
        "-qm",
        "start");
    mutastic("gate", "open", project.toString());
  }

  @AfterEach
  void closed() {
    mutastic("gate", "close", project.toString());
  }

  @Test
  void aCsvSourceRowTakenAwayHoldsTheTask() throws Exception {
    edit("@CsvSource(\"1, 1, 1\", \"2, 1, 2\")", "@CsvSource(\"1, 1, 1\")");

    var held = mutastic("gate", "check", project.toString());

    assertThat(held.exit()).as(held.out()).isEqualTo(Main.FAILED);
    assertThat(held.out())
        .contains("fewer tests run than when the task started (7, 0 disabled; now 6, 0 disabled)");
  }

  @Test
  void disablingAParameterizedTestHoldsTheTask() throws Exception {
    edit(
        "    @ParameterizedTest\n    @CsvSource(\"1, 1, 1\"",
        "    @Disabled\n    @ParameterizedTest\n    @CsvSource(\"1, 1, 1\"");
    edit(
        "import org.junit.jupiter.params.ParameterizedTest",
        "import org.junit.jupiter.api.Disabled\nimport org.junit.jupiter.params.ParameterizedTest");

    var held = mutastic("gate", "check", project.toString());

    assertThat(held.exit()).as(held.out()).isEqualTo(Main.FAILED);
    assertThat(held.out())
        .contains("fewer tests run than when the task started (7, 0 disabled; now 7, 2 disabled)");
  }

  /// A row a method makes shows only in a run.
  @Test
  void aMethodSourceRowTakenAwayHoldsTheTask() throws Exception {
    edit(
        "Stream.of(Arguments.of(50, 0), Arguments.of(250, 25))",
        "Stream.of(Arguments.of(250, 25))");

    var held = mutastic("gate", "check", project.toString());

    assertThat(held.exit()).as(held.out()).isEqualTo(Main.FAILED);
    assertThat(held.out())
        .contains(
            "fewer test cases run than when the task started (8 run, 0 skipped; now 7 run, 0"
                + " skipped)");
  }

  /// A condition that turns a test off is decided only as it runs.
  @Test
  void aConditionThatTurnsATestOffHoldsTheTask() throws Exception {
    edit(
        "    @ParameterizedTest\n    @ValueSource",
        "    @DisabledIfSystemProperty(named = \"java.version\", matches = \".*\")\n"
            + "    @ParameterizedTest\n    @ValueSource");
    edit(
        "import org.junit.jupiter.params.ParameterizedTest",
        "import org.junit.jupiter.api.condition.DisabledIfSystemProperty\n"
            + "import org.junit.jupiter.params.ParameterizedTest");

    var held = mutastic("gate", "check", project.toString());

    assertThat(held.exit()).as(held.out()).isEqualTo(Main.FAILED);
    assertThat(held.out())
        .contains("fewer test cases run than when the task started (8 run, 0 skipped; now 6 run, 1")
        .contains("turned off or aborted by an assumption");
  }

  /// A case an assumption aborts didn't check anything: it's counted as skipped.
  @Test
  void aCaseAnAssumptionAbortsIsReported() throws Exception {
    edit(
        "    fun evens(n: Int) {\n",
        "    fun evens(n: Int) {\n        org.junit.jupiter.api.Assumptions.assumeTrue(n > 2)\n");

    var held = mutastic("gate", "check", project.toString());

    assertThat(held.exit()).as(held.out()).isEqualTo(Main.FAILED);
    assertThat(held.out())
        .contains(
            "fewer test cases run than when the task started (8 run, 0 skipped; now 7 run, 1"
                + " skipped)");
  }

  /// Where the start couldn't be counted, a pass says what wasn't compared.
  @Test
  void aPassSaysWhenTheStartCouldntBeCounted() throws Exception {
    GateCount.stop(project.resolve("build/mutastic"));
    edit("// Only parameterized tests", "// Only parameterized tests, still");

    var passed = mutastic("gate", "check", project.toString());

    assertThat(passed.exit()).as(passed.out()).isEqualTo(Main.PASSED);
    assertThat(passed.out())
        .contains(
            "The test cases running when the task started couldn't be counted (no count was started)");
  }

  private void edit(String from, String to) throws Exception {
    var file = project.resolve(TEST);
    var text = Files.readString(file);
    assertThat(text).contains(from);
    Files.writeString(file, text.replace(from, to));
  }

  private void git(String... args) throws Exception {
    var command = new java.util.ArrayList<>(List.of("git"));
    command.addAll(List.of(args));
    int exit =
        new ProcessBuilder(command).directory(project.toFile()).inheritIO().start().waitFor();
    assertThat(exit).as("git %s", String.join(" ", args)).isZero();
  }
}
