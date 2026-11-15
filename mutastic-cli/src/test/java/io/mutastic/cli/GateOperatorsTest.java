package io.mutastic.cli;

import static io.mutastic.cli.Samples.mutastic;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The operators a gated task is held to, on `samples/checkout`: a task that charges for the
/// quantity, with a test that buys one item, passes on the defaults, but stronger's keep-operand
/// mutant, `quantity * price` becoming `price`, survives it. The set is chosen when the gate opens,
/// by `--operators`, `mutastic.yaml`'s `gate:` or its `operators:`, and a check can't lower it.
class GateOperatorsTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "checkout");
  private static final Path CHECKOUT = Path.of("src/main/java/com/example/checkout/Checkout.java");
  private static final Path TEST = Path.of("src/test/java/com/example/checkout/CheckoutTest.java");

  /// `quantity * price` becomes `price`: what only the stronger set makes on the task's line.
  private static final String KEEP_OPERAND = "mf248f66b";

  @TempDir Path work;

  private Path project;

  /// The checkout before the task: every item at one price, whatever the quantity.
  @BeforeEach
  void aProjectInGit() throws Exception {
    project = Samples.copy(SAMPLE, work.resolve("checkout"));
    Files.writeString(project.resolve(".gitignore"), "build/\n.gradle/\n");
    var checkout = project.resolve(CHECKOUT);
    Files.writeString(
        checkout,
        Files.readString(checkout).replace("int total = quantity * price;", "int total = price;"));
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
  }

  @AfterEach
  void stopCounting() {
    GateCount.stop(project.resolve("build/mutastic"));
  }

  @Test
  void aTaskThatPassesOnTheDefaultsIsHeldOnStronger() throws Exception {
    mutastic("gate", "open", project.toString());
    chargeForTheQuantity();
    var onDefaults = mutastic("gate", "check", project.toString());
    mutastic("gate", "close", project.toString());
    Files.writeString(project.resolve("mutastic.yaml"), "gate:\n  operators:\n    - stronger\n");
    var opened = mutastic("gate", "open", project.toString());
    var onStronger = mutastic("gate", "check", project.toString());

    assertThat(onDefaults.exit()).as(onDefaults.out()).isEqualTo(Main.PASSED);
    assertThat(onDefaults.out())
        .contains("mutastic gate: passed: the tests catch")
        .contains("(operators: defaults)");
    assertThat(opened.out())
        .contains("(operators: stronger): before you finish")
        .contains("--operators=stronger --format=agent");
    assertThat(onStronger.exit()).as(onStronger.out()).isEqualTo(Main.FAILED);
    assertThat(onStronger.out())
        .contains("holds it, so it isn't done (missed: " + KEEP_OPERAND + "; operators: stronger)")
        .contains("no test needs `quantity` in `quantity * price` in `pay()`");
  }

  /// The open's set is kept with the task: a check given another is refused, and one that changes
  /// `mutastic.yaml` in between still runs what the open chose.
  @Test
  void aCheckCantLowerTheSetTheOpenChose() throws Exception {
    mutastic("gate", "open", project.toString(), "--operators=stronger");
    chargeForTheQuantity();
    Files.writeString(project.resolve("mutastic.yaml"), "gate:\n  operators:\n    - defaults\n");
    var lowered = mutastic("gate", "check", project.toString(), "--operators=defaults");
    var same = mutastic("gate", "check", project.toString(), "--operators=stronger");
    var configured = mutastic("gate", "check", project.toString());

    assertThat(lowered.exit()).isEqualTo(Main.USAGE);
    assertThat(lowered.err())
        .contains(
            "the gate opened with operators: stronger, and a check runs what the open chose:"
                + " `mutastic gate close`, then `mutastic gate open --operators=defaults`, to"
                + " choose again");
    for (var held : java.util.List.of(same, configured)) {
      assertThat(held.exit()).as(held.out()).isEqualTo(Main.FAILED);
      assertThat(held.out()).contains("missed: " + KEEP_OPERAND + "; operators: stronger");
    }
  }

  /// The task: `pay()` charges for the quantity, with a test that buys one item, which catches what
  /// the defaults make of the line but not stronger's keep-operand mutant.
  private void chargeForTheQuantity() throws Exception {
    var checkout = project.resolve(CHECKOUT);
    Files.writeString(
        checkout,
        Files.readString(checkout).replace("int total = price;", "int total = quantity * price;"));
    var test = project.resolve(TEST);
    Files.writeString(
        test,
        Files.readString(test)
            .replaceFirst(
                "\\}\\s*$",
                """

                  @Test
                  void oneItemAtTwoHundredAndFiftyCostsTwoHundredAndTwentyFive() {
                    org.junit.jupiter.api.Assertions.assertEquals(
                        225, new Checkout().pay(1, 250, new ArrayList<>()));
                  }
                }
                """));
  }

  private void git(String... args) throws Exception {
    var command = new java.util.ArrayList<>(java.util.List.of("git"));
    command.addAll(java.util.List.of(args));
    int exit =
        new ProcessBuilder(command).directory(project.toFile()).inheritIO().start().waitFor();
    assertThat(exit).as("git %s", String.join(" ", args)).isZero();
  }
}
