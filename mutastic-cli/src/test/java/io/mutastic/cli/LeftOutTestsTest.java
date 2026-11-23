package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A way past a test that fails on its own, on `samples/pricing` with one test made to fail: the
/// run stops, as it always has, and says how to leave the test out, which a person does for a run
/// with `--exclude-tests=Class#method` or for every run under `exclude-tests:` with a reason. An
/// agent is never offered it. The Rust edition's `tests/samples.rs` has the same checks.
class LeftOutTestsTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");
  private static final String FAILING = "PricingTest#bigOrdersGetTenPercentOff";

  @TempDir static Path work;
  private static Path red;

  @BeforeAll
  static void aTestFailsOnItsOwn() throws Exception {
    red = Samples.copy(SAMPLE, work.resolve("red"));
    var test = red.resolve("src/test/java/com/example/pricing/PricingTest.java");
    Files.writeString(test, Files.readString(test).replace("assertEquals(25,", "assertEquals(26,"));
  }

  @Test
  void theRunStopsAndSaysHowToLeaveTheTestOut() {
    var person = Samples.mutastic("run", red.toString());
    var agent = Samples.mutastic("run", red.toString(), "--format=agent");

    assertThat(person.exit()).isEqualTo(Main.TESTS_FAIL);
    assertThat(person.err())
        .contains(
            "To run without it until it's fixed, list it under exclude-tests: in mutastic.yaml"
                + " with a reason, or for this run add --exclude-tests="
                + FAILING
                + "\n");
    assertThat(agent.exit()).isEqualTo(Main.TESTS_FAIL);
    assertThat(agent.err()).contains(Main.AGENT_WAY_PAST).doesNotContain("--exclude-tests");
    assertThat(agent.out()).doesNotContain("--exclude-tests");
  }

  /// The option the stop offers leaves out that one method, not its class: the class's other tests
  /// run, and the summary names it.
  @Test
  void theOfferedOptionLeavesOutTheOneMethod() {
    var run =
        Samples.mutastic(
            Map.of("MUTASTIC_FORMAT", "full"), "run", red.toString(), "--exclude-tests=" + FAILING);

    assertThat(run.exit()).isNotEqualTo(Main.TESTS_FAIL);
    assertThat(run.out())
        .contains("     Tests  2 on ")
        .contains("  Left out  " + FAILING + " (--exclude-tests)\n")
        .contains("  · 1 test left out\n");
  }

  /// One under `exclude-tests:` is left out of every run, named with its reason, and so is one the
  /// command line adds; `run-finished` names both, the command line's with no reason.
  @Test
  void mutasticYamlLeavesItOutWithItsReason() throws Exception {
    var project = Samples.copy(red, work.resolve("configured"));
    Files.writeString(
        project.resolve("mutastic.yaml"),
        "exclude-tests:\n  - test: " + FAILING + "\n    reason: rounding changes next week\n");

    var human =
        Samples.mutastic(
            Map.of("MUTASTIC_FORMAT", "full"),
            "run",
            project.toString(),
            "--exclude-tests=PricingTest#smallOrdersPayFullPrice");
    var events = Samples.mutastic(Map.of("MUTASTIC_FORMAT", "jsonl"), "run", project.toString());

    assertThat(human.out())
        .contains(
            "  Left out  "
                + FAILING
                + ": rounding changes next week\n"
                + "            PricingTest#smallOrdersPayFullPrice (--exclude-tests)\n")
        .contains("  · 2 tests left out\n");
    assertThat(events.out().lines().reduce((first, last) -> last).orElseThrow())
        .contains(
            "\"testsLeftOut\":[{\"test\":\""
                + FAILING
                + "\",\"reason\":\"rounding changes next week\"}]");
  }
}
