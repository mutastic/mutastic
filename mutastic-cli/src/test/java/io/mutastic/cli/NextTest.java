package io.mutastic.cli;

import static io.mutastic.cli.Samples.mutastic;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `mutastic next` and `mutastic baseline` on the pricing sample: the queue's head for an agent, a
/// test that catches it dropping it, and a baseline that tells older misses from new ones, which
/// the gate then leaves alone.
class NextTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");
  private static final Path PRICING = Path.of("src/main/java/com/example/pricing/Pricing.java");
  private static final Path TEST = Path.of("src/test/java/com/example/pricing/PricingTest.java");

  @TempDir Path work;

  private Path project;

  @BeforeEach
  void aProjectInGit() throws Exception {
    project = Samples.copy(SAMPLE, work.resolve("pricing"));
    Files.writeString(project.resolve(".gitignore"), "build/\n.gradle/\n");
    git("init", "-q", "-b", "main");
    commit("start");
  }

  /// One task, whole, then gone once a test catches it: the next `next` runs only what was left.
  @Test
  void anAgentGetsTheHeadAndATestThatCatchesItDropsIt() throws Exception {
    var first = mutastic("next", project.toString(), "--format=agent");
    var test = project.resolve(TEST);
    Files.writeString(
        test,
        Files.readString(test)
            .replaceFirst(
                "\\}\\s*$",
                """

                  @Test
                  void ordersOfExactlyAHundredGetTheDiscount() {
                    assertEquals(10, new Pricing().discount(100));
                    assertEquals(0, new Pricing().discount(99));
                  }
                }
                """));
    var after = mutastic("next", project.toString(), "--format=agent");

    assertThat(first.exit()).isEqualTo(Main.FAILED);
    assertThat(first.out())
        .startsWith("mutastic next: 1 of 1 line with missed mutants in pricing")
        .contains("why first: a condition or a removal")
        .contains("done when: mutastic verify ")
        .contains("then: run `mutastic next ");
    assertThat(after.exit()).isEqualTo(Main.PASSED);
    assertThat(after.out()).isEqualTo("mutastic next: nothing missed is left in pricing\n");
  }

  /// The baseline takes in today's misses once, not again; next says which are older; and the gate
  /// leaves an older miss on a line a task touched to the person, but not one the task baselined.
  @Test
  void aBaselineToldOlderMissesFromNewOnes() throws Exception {
    var taken = mutastic("baseline", project.toString());
    var again = mutastic("baseline", project.toString());
    var next = mutastic("next", project.toString(), "--top=5", "--color=never");
    commit("baseline");
    mutastic("gate", "open", project.toString());
    var pricing = project.resolve(PRICING);
    Files.writeString(
        pricing,
        Files.readString(pricing).replace("if (total >= 100) {", "if (total >= 100) { // tiers"));
    var touched = mutastic("gate", "check", project.toString());
    var baseline = project.resolve(Baseline.FILE);
    Files.writeString(baseline, Files.readString(baseline) + "  - id: m0000abcd\n");
    var added = mutastic("gate", "check", project.toString());

    assertThat(taken.exit()).isEqualTo(Main.PASSED);
    assertThat(taken.out()).contains("mutants no test catches are in .mutastic/baseline.yaml");
    assertThat(Files.readString(baseline)).contains("  - id: m").contains("Pricing#discount");
    assertThat(again.exit()).isEqualTo(Main.FAILED);
    assertThat(again.out()).contains("there's a baseline already");
    assertThat(next.out())
        .contains("(0 new, 1 in the baseline)")
        .contains("in the baseline: missed");
    assertThat(touched.exit()).isEqualTo(Main.PASSED);
    assertThat(touched.out()).contains("missed before the task and in .mutastic/baseline.yaml");
    assertThat(added.exit()).isEqualTo(Main.FAILED);
    assertThat(added.out()).contains("the task added m0000abcd to .mutastic/baseline.yaml itself");
  }

  private void commit(String message) throws Exception {
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
        message);
  }

  private void git(String... args) throws Exception {
    var command = new java.util.ArrayList<>(java.util.List.of("git"));
    command.addAll(java.util.List.of(args));
    int exit =
        new ProcessBuilder(command).directory(project.toFile()).inheritIO().start().waitFor();
    assertThat(exit).as("git %s", String.join(" ", args)).isZero();
  }
}
