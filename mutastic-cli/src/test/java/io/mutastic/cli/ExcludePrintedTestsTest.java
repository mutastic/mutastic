package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A test named as a run prints it is a test `--exclude-tests` takes, on `samples/parameterized`,
/// whose tests take parameters: the full name with its parameter types, as `caughtBy` has it,
/// leaves the test out, and so does the short one the summary shows. A name that matches no test
/// says so in every format, with the test it's close to, rather than leave nothing out and say
/// nothing (decision 28).
class ExcludePrintedTestsTest {
  private static final Path SAMPLE =
      Path.of(System.getProperty("mutastic.samples"), "parameterized");

  /// The only test that reaches `isEven()`, whose 2 mutants are not covered without it.
  private static final Pattern EVENS =
      Pattern.compile(
          "\"caughtBy\":\"(?<test>com\\.example\\.calc\\.CalculatorTest#evens\\([^\"]*\\))\"");

  private static final String NOT_COVERED_WITHOUT_IT = "Calculator.java:24 isEven() (2 mutants)";

  @TempDir static Path work;
  private static Path project;
  private static String printed;

  @BeforeAll
  static void aRunPrintsTheTest() throws Exception {
    project = Samples.copy(SAMPLE, work.resolve("parameterized"));
    var events = Samples.mutastic(Map.of("MUTASTIC_FORMAT", "jsonl"), "run", project.toString());
    var found = EVENS.matcher(events.out());
    assertThat(found.find()).as(events.out()).isTrue();
    printed = found.group("test");
  }

  @Test
  void theNameARunPrintsLeavesTheTestOut() {
    var full = run("--exclude-tests=" + printed);
    var shortName = run("--exclude-tests=" + printed.substring("com.example.calc.".length()));

    assertThat(printed).isEqualTo("com.example.calc.CalculatorTest#evens(int)");
    assertThat(full.out())
        .contains(NOT_COVERED_WITHOUT_IT)
        .contains("  Left out  " + printed + " (--exclude-tests)\n")
        .contains("  · 1 test left out\n")
        .doesNotContain("left out nothing");
    assertThat(shortName.out()).contains(NOT_COVERED_WITHOUT_IT).doesNotContain("left out nothing");
  }

  @Test
  void aNameThatMatchesNoTestSaysSoInEveryFormat() {
    var said =
        "CalculatorTest#evens(String) names no test of parameterized."
            + " There's one called CalculatorTest#evens(int).";

    var human = run("--exclude-tests=CalculatorTest#evens(String)");
    var agent =
        Samples.mutastic(
            "run",
            project.toString(),
            "--format=agent",
            "--exclude-tests=CalculatorTest#evens(String)");
    var events =
        Samples.mutastic(
            Map.of("MUTASTIC_FORMAT", "jsonl"),
            "run",
            project.toString(),
            "--exclude-tests=CalculatorTest#evens(String)");

    assertThat(human.out())
        .contains(" ! left out nothing: " + said + "\n")
        .doesNotContain(NOT_COVERED_WITHOUT_IT)
        .doesNotContain("tests left out")
        .doesNotContain("test left out");
    assertThat(agent.out()).contains("left out nothing: " + said + "\n");
    assertThat(events.out().lines().reduce((first, last) -> last).orElseThrow())
        .contains(
            "\"testsLeftOut\":[{\"test\":\"CalculatorTest#evens(String)\",\"reason\":null,"
                + "\"matched\":false,\"maybe\":\"CalculatorTest#evens(int)\"}]");
  }

  private static Samples.Result run(String... options) {
    var args = new java.util.ArrayList<String>();
    args.add("run");
    args.add(project.toString());
    args.addAll(java.util.List.of(options));
    return Samples.mutastic(Map.of("MUTASTIC_FORMAT", "full"), args.toArray(String[]::new));
  }
}
