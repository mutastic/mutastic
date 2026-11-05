package io.mutastic.cli;

import static io.mutastic.cli.Samples.mutastic;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `mutastic gate`: a coding agent's task held to the mutants on the lines it changed. Done when
/// each is caught or proposed for a person to ignore; not by an ignore of its own, or a test taken
/// away; and with a hook, after its tries it lets the agent stop and says what's left.
class GateTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");
  private static final Path PRICING = Path.of("src/main/java/com/example/pricing/Pricing.java");

  @TempDir Path work;

  private Path project;
  private final InputStream stdin = System.in;

  @BeforeEach
  void aProjectInGit() throws Exception {
    project = Samples.copy(SAMPLE, work.resolve("pricing"));
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
  void stdinBack() {
    System.setIn(stdin);
  }

  @Test
  void aTaskIsDoneWhenItsMutantsAreCaughtOrProposed() throws Exception {
    var opened = mutastic("gate", "open", project.toString());
    var unchanged = mutastic("gate", "check", project.toString());
    aDiscountForOrdersOverFifty();
    var gap = mutastic("gate", "check", project.toString());

    assertThat(opened.out()).contains("this task is gated from").contains("--format=agent");
    assertThat(unchanged.exit()).isEqualTo(Main.PASSED);
    assertThat(unchanged.out()).contains("passed: the task changed no lines mutastic mutates");
    assertThat(gap.exit()).isEqualTo(Main.FAILED);
    assertThat(gap.out())
        .contains("on the lines the task changed")
        .contains("so it isn't done")
        .contains("1. missed m")
        .contains("or propose: mutastic ignore")
        .doesNotContain("or ignore:");

    for (var id : missedIds(gap.out())) {
      mutastic("ignore", id, project.toString(), "--propose", "--reason=fifty is checked by hand");
    }
    var proposed = mutastic("gate", "check", project.toString());
    assertThat(proposed.exit()).isEqualTo(Main.PASSED);
    assertThat(proposed.out())
        .contains("the rest wait for a person, proposed in .mutastic/proposals.yaml");
  }

  /// The task can't settle a mutant itself: an ignore it made, or a test taken away, holds it.
  @Test
  void aTaskCantIgnoreAMutantOrTakeATestAway() throws Exception {
    mutastic("gate", "open", project.toString());
    aDiscountForOrdersOverFifty();
    var gap = mutastic("gate", "check", project.toString());
    var id = missedIds(gap.out())[0];
    mutastic("ignore", id, project.toString(), "--reason=mine to decide");
    var selfIgnored = mutastic("gate", "check", project.toString());
    mutastic("ignore", "--remove", id, project.toString());
    var test = project.resolve("src/test/java/com/example/pricing/PricingTest.java");
    Files.writeString(
        test,
        Files.readString(test).replaceFirst("@Test", "@org.junit.jupiter.api.Disabled @Test"));
    var disabled = mutastic("gate", "check", project.toString());

    assertThat(selfIgnored.exit()).isEqualTo(Main.FAILED);
    assertThat(selfIgnored.out())
        .contains("the task ignored " + id + " itself")
        .contains("mutastic ignore ID --propose");
    assertThat(disabled.exit()).isEqualTo(Main.FAILED);
    assertThat(disabled.out()).contains("fewer tests run than when the task started");
  }

  /// As a Claude Code hook: open once a session, a block with the cards as its reason, and after
  /// its tries a message for the person instead, so the agent isn't sent round for ever.
  @Test
  void asAHookItOpensOnceASessionAndStopsSendingTheAgentBack() throws Exception {
    var first = hooked("{\"session_id\":\"s1\"}", "gate", "open", "--hook=claude");
    var again = hooked("{\"session_id\":\"s1\"}", "gate", "open", "--hook=claude");
    aDiscountForOrdersOverFifty();
    var blocked = hooked("{\"session_id\":\"s1\"}", "gate", "check", "--hook=claude", "--tries=1");
    var gaveUp = hooked("{\"session_id\":\"s1\"}", "gate", "check", "--hook=claude", "--tries=1");
    mutastic("gate", "close", project.toString());
    var closed = hooked("{\"session_id\":\"s1\"}", "gate", "check", "--hook=claude");

    assertThat(first.out()).contains("this task is gated from");
    assertThat(again.out()).isEmpty();
    assertThat(blocked.exit()).isEqualTo(Main.PASSED);
    assertThat(blocked.out()).startsWith("{\"decision\": \"block\", \"reason\": \"mutastic gate: ");
    assertThat(gaveUp.out())
        .startsWith("{\"systemMessage\": ")
        .contains("the agent was sent back 1 times, so it's over to you");
    assertThat(closed.out()).isEmpty(); // nothing gated: the agent may stop
  }

  @Test
  void gateNeedsToBeToldWhatToDo() {
    var bare = mutastic("gate");
    assertThat(bare.exit()).isEqualTo(Main.USAGE);
    assertThat(bare.err()).contains("gate needs what to do: open, check or close");
    assertThat(mutastic("gate", "open", "--help").out()).contains("Usage: mutastic gate open");
  }

  /// `discount()` for orders over fifty as well: a change whose boundary no test checks.
  private void aDiscountForOrdersOverFifty() throws Exception {
    var pricing = project.resolve(PRICING);
    Files.writeString(
        pricing,
        Files.readString(pricing)
            .replace("if (total >= 100) {", "if (total >= 100 || total > 50) {"));
  }

  private Samples.Result hooked(String input, String... args) {
    System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    var all = new java.util.ArrayList<>(java.util.List.of(args));
    all.add(2, project.toString());
    return mutastic(all.toArray(String[]::new));
  }

  private static String[] missedIds(String out) {
    var missed = java.util.regex.Pattern.compile("(?:missed|also missed) (?<id>m[0-9a-f]{8})");
    return missed
        .matcher(out)
        .results()
        .map(found -> found.group("id"))
        .distinct()
        .toArray(String[]::new);
  }

  private void git(String... args) throws Exception {
    var command = new java.util.ArrayList<>(java.util.List.of("git"));
    command.addAll(java.util.List.of(args));
    int exit =
        new ProcessBuilder(command).directory(project.toFile()).inheritIO().start().waitFor();
    assertThat(exit).as("git %s", String.join(" ", args)).isZero();
  }
}
