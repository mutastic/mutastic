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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    Files.writeString(project.resolve(".gitignore"), "build/\n.gradle/\n");
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

    assertThat(opened.out())
        .contains("this task is gated from")
        .contains("--format=agent")
        .contains("`mutastic gate check` says when it's done");
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

  /// What holds the gate is named, each mutant by id, those no test reaches too, and a proposed one
  /// is said to wait on a person, not to be missed: the shape an eval was blocked by.
  @Test
  void everyMutantThatHoldsTheGateIsNamed() throws Exception {
    mutastic("gate", "open", project.toString());
    aDiscountForOrdersOverFifty();
    var pricing = project.resolve(PRICING);
    var text = Files.readString(pricing);
    Files.writeString(
        pricing,
        text.substring(0, text.lastIndexOf('}'))
            + "  public int surcharge(int total) {\n    return total > 10 ? 1 : 0;\n  }\n}\n");
    var gap = mutastic("gate", "check", project.toString());
    for (var id : missedIds(gap.out())) {
      mutastic("ignore", id, project.toString(), "--propose", "--reason=fifty is checked by hand");
    }
    var held = mutastic("gate", "check", project.toString());
    var uncovered =
        java.util.regex.Pattern.compile("no test reaches: (?<ids>m[0-9a-f]{8}(?:, m[0-9a-f]{8})*)")
            .matcher(held.out());

    assertThat(held.exit()).isEqualTo(Main.FAILED);
    assertThat(uncovered.find()).as(held.out()).isTrue();
    assertThat(held.out())
        .contains("Proposed, waiting on a person, and not holding it: " + missedIds(gap.out())[0])
        .doesNotContain("missed: ")
        .doesNotContain("1. missed");
    for (var id : uncovered.group("ids").split(", ", -1)) {
      mutastic("ignore", id, project.toString(), "--propose", "--reason=never called yet");
    }
    assertThat(mutastic("gate", "check", project.toString()).exit()).isEqualTo(Main.PASSED);
  }

  /// A task that changed only tests changed no line mutastic mutates: there's no mutant to gate,
  /// and it passes once its tests do, before the ignores are looked at, as in the Rust edition;
  /// taking a test away still holds it.
  @Test
  void aTaskThatChangedOnlyTestsPasses() throws Exception {
    mutastic("gate", "open", project.toString());
    var test = project.resolve("src/test/java/com/example/pricing/PricingTest.java");
    Files.writeString(test, Files.readString(test).replace("@Test", "// a note\n  @Test"));
    var checked = mutastic("gate", "check", project.toString());
    Files.writeString(
        test,
        Files.readString(test).replaceFirst("@Test", "@org.junit.jupiter.api.Disabled @Test"));
    var disabled = mutastic("gate", "check", project.toString());

    assertThat(checked.exit()).isEqualTo(Main.PASSED);
    assertThat(checked.out())
        .contains("passed: the tests pass, and the task changed no lines mutastic mutates");
    assertThat(disabled.exit()).isEqualTo(Main.FAILED);
    assertThat(disabled.out()).contains("fewer tests run than when the task started");
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
    anotherTry();
    var gaveUp = hooked("{\"session_id\":\"s1\"}", "gate", "check", "--hook=claude", "--tries=1");
    mutastic("gate", "close", project.toString());
    var closed = hooked("{\"session_id\":\"s1\"}", "gate", "check", "--hook=claude");

    assertThat(first.out())
        .contains("this task is gated from")
        .contains("the gate checks them when you finish") // so the agent doesn't check twice
        .doesNotContain("says when it's done");
    assertThat(again.out()).isEmpty();
    assertThat(blocked.exit()).isEqualTo(Main.PASSED);
    assertThat(blocked.out()).startsWith("{\"decision\": \"block\", \"reason\": \"mutastic gate: ");
    assertThat(gaveUp.out())
        .startsWith("{\"systemMessage\": ")
        .contains("the agent was sent back 1 times, so it's over to you");
    assertThat(closed.out()).isEmpty(); // nothing gated: the agent may stop
  }

  /// An ignore the person accepts between turns, after the gate let the agent stop and before their
  /// next prompt, is the task's to start from: the next check passes. One the agent accepts in its
  /// turn, or writes into `.mutastic/ignores.yaml` itself, stays the agent's, after the person's
  /// next prompt too: re-reading the ignores at every prompt would pass whatever the agent ignored.
  @ParameterizedTest
  @ValueSource(strings = {"person", "agent", "file"})
  void anIgnoreThePersonAcceptsBetweenTurnsDoesntHoldTheGate(String who) throws Exception {
    var session = "{\"session_id\":\"s1\"}";
    hooked(session, "gate", "open", "--hook=claude");
    aDiscountForOrdersOverFifty();
    var blocked = hooked(session, "gate", "check", "--hook=claude");
    var ids = missedIds(blocked.out());
    assertThat(ids).as(blocked.out()).isNotEmpty();
    for (var id : ids) {
      mutastic("ignore", id, project.toString(), "--propose", "--reason=fifty is checked by hand");
    }
    switch (who) {
      case "agent" -> acceptAll(ids);
      // The agent moves the proposals into the ignores by hand, rather than accept them.
      case "file" ->
          Files.move(
              project.resolve(".mutastic/proposals.yaml"),
              project.resolve(".mutastic/ignores.yaml"));
      default -> {}
    }
    var stop = hooked(session, "gate", "check", "--hook=claude");
    if (who.equals("person")) {
      assertThat(stop.out()).startsWith("{\"systemMessage\": \"mutastic gate: passed");
      acceptAll(ids); // between turns
    } else {
      // Held; sent back, the agent changes nothing, so it's over to the person, the ignores still
      // the agent's.
      assertThat(stop.out())
          .startsWith("{\"decision\": \"block\"")
          .contains("the task ignored " + ids[0]);
      assertThat(hooked(session, "gate", "check", "--hook=claude").out())
          .startsWith("{\"systemMessage\": ")
          .contains("so it's over to you");
    }
    hooked(session, "gate", "open", "--hook=claude");
    var next = hooked(session, "gate", "check", "--hook=claude");

    if (who.equals("person")) {
      assertThat(next.out()).startsWith("{\"systemMessage\": \"mutastic gate: passed");
    } else {
      assertThat(next.out())
          .as(who)
          .startsWith("{\"decision\": \"block\"")
          .contains("the task ignored " + ids[0]);
    }
  }

  private void acceptAll(String[] ids) {
    for (var id : ids) {
      assertThat(mutastic("ignore", "--accept", id, project.toString()).exit())
          .isEqualTo(Main.PASSED);
    }
  }

  /// Sent back, an agent that changes nothing has declined, as it may when the person said no
  /// tests: it's over to the person at once, not after every try.
  @Test
  void anAgentThatChangesNothingWhenSentBackIsLetStop() throws Exception {
    hooked("{\"session_id\":\"s1\"}", "gate", "open", "--hook=claude");
    aDiscountForOrdersOverFifty();
    var blocked = hooked("{\"session_id\":\"s1\"}", "gate", "check", "--hook=claude");
    var declined = hooked("{\"session_id\":\"s1\"}", "gate", "check", "--hook=claude");
    var again = hooked("{\"session_id\":\"s1\"}", "gate", "check", "--hook=claude");

    assertThat(blocked.out()).startsWith("{\"decision\": \"block\"");
    assertThat(declined.out())
        .startsWith("{\"systemMessage\": ")
        .contains("nothing changed since the agent was sent back, so it's over to you")
        .contains("1. missed m");
    assertThat(again.out()).startsWith("{\"decision\": \"block\""); // a new stop, a new try
  }

  /// A test the task added that fails is the task's to make pass, and to leave in place: taken away
  /// later, it holds the gate, or deleting it would pass a task the test said was wrong. One that
  /// was there when the task started is most likely broken by the change: the agent isn't to
  /// rewrite what it expects.
  @Test
  void aFailingTestIsLeftInPlaceAndOneThatWasThereIsntTheAgentsToRewrite() throws Exception {
    mutastic("gate", "open", project.toString());
    var pricing = project.resolve(PRICING);
    Files.writeString(pricing, Files.readString(pricing).replace("total / 10", "total / 5"));
    var broken = mutastic("gate", "check", project.toString());
    git("checkout", "--", PRICING.toString());
    var test = project.resolve("src/test/java/com/example/pricing/PricingTest.java");
    var original = Files.readString(test);
    Files.writeString(
        test,
        original.replaceFirst(
            "\\}\\s*$",
            """

              @org.junit.jupiter.api.Test
              void hugeOrders() {
                assertEquals(1, new Pricing().discount(1000));
              }
            }
            """));
    aDiscountForOrdersOverFifty();
    var added = mutastic("gate", "check", project.toString());
    Files.writeString(test, original);
    var deleted = mutastic("gate", "check", project.toString());

    assertThat(broken.exit()).isEqualTo(Main.FAILED);
    assertThat(broken.out())
        .contains("PricingTest#bigOrdersGetTenPercentOff was there when the task started")
        .contains("Find out whether the change or the test is wrong")
        .contains("Don't change what a test expects, or take it away, to make it pass")
        .contains("if you're not sure, ask")
        .doesNotContain("Make them pass first")
        .doesNotContain("spec or plan");
    assertThat(added.exit()).isEqualTo(Main.FAILED);
    assertThat(added.out())
        .contains("PricingTest#hugeOrders")
        .doesNotContain("..")
        .contains(
            "Make them pass first, unless the spec or plan is what's wrong, in which case say so"
                + " and stop. Either way, leave a failing test in place")
        .doesNotContain("there when the task started");
    assertThat(deleted.exit()).isEqualTo(Main.FAILED);
    assertThat(deleted.out())
        .contains(
            "com.example.pricing.PricingTest#hugeOrders failed at an earlier check, and it's gone"
                + " now. Put it back")
        .contains("unless the spec or plan is what's wrong, in which case say so and stop")
        .doesNotContain("bigOrdersGetTenPercentOff"); // still there, and passing
  }

  /// The gate runs the tests even with no mutant on the lines the task changed: a task that breaks
  /// a test with a line holding none (a constant, an import, a log line) is held, as a red suite
  /// is, with the same hand-over, while a plain `run --since` of the same change runs no test. Only
  /// a task that changed nothing skips them.
  @Test
  void theGateRunsTheTestsWithNoMutantOnTheChangedLines() throws Exception {
    Files.writeString(
        project.resolve("src/test/java/com/example/pricing/ThresholdTest.java"),
        """
        package com.example.pricing;

        import static org.junit.jupiter.api.Assertions.assertEquals;

        import org.junit.jupiter.api.Test;

        class ThresholdTest {
          @Test
          void shippingIsFreeFromFifty() {
            assertEquals(5, new Pricing().shipping(49));
          }
        }
        """);
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
        "t");
    var session = "{\"session_id\":\"s1\"}";
    hooked(session, "gate", "open", "--hook=claude");
    var pricing = project.resolve(PRICING);
    var text = Files.readString(pricing);
    Files.writeString(pricing, text.replace("this(50);", "this(40);"));
    var blocked = hooked(session, "gate", "check", "--hook=claude");
    var handedOver = hooked(session, "gate", "check", "--hook=claude");
    var broken = mutastic("gate", "check", project.toString());
    var plain = mutastic("run", project.toString(), "--since=HEAD", "--format=brief");
    Files.writeString(pricing, text.replace("this(50);", "this(60);"));
    var unbroken = mutastic("gate", "check", project.toString());
    Files.writeString(pricing, text);
    var orders = project.resolve("src/test/resources/orders.properties");
    Files.writeString(orders, "small=150\n");
    var data = mutastic("gate", "check", project.toString());
    git("checkout", "--", "src/test/resources/orders.properties");
    var untouched = mutastic("gate", "check", project.toString());

    assertThat(blocked.out())
        .startsWith("{\"decision\": \"block\"")
        .contains("ThresholdTest#shippingIsFreeFromFifty was there when the task started");
    assertThat(handedOver.out())
        .startsWith("{\"systemMessage\": ")
        .contains("nothing changed since the agent was sent back, so it's over to you");
    assertThat(broken.exit()).isEqualTo(Main.FAILED);
    assertThat(broken.out())
        .contains(
            "ThresholdTest#shippingIsFreeFromFifty was there when the task started: if it passed"
                + " then, the task's change broke it");
    assertThat(plain.exit()).isEqualTo(Main.PASSED);
    assertThat(plain.out()).contains("    Tests  none ran: no mutant to run\n");
    assertThat(unbroken.exit()).as(unbroken.out()).isEqualTo(Main.PASSED);
    assertThat(unbroken.out())
        .isEqualTo(
            "mutastic gate: passed: the tests pass, and the lines the task changed hold no mutant"
                + " to run\n");
    assertThat(data.exit()).isEqualTo(Main.FAILED);
    assertThat(data.out())
        .contains("PricingTest#smallOrdersPayFullPrice was there when the task started");
    assertThat(untouched.exit()).isEqualTo(Main.PASSED);
    assertThat(untouched.out())
        .isEqualTo("mutastic gate: passed: the task changed no lines mutastic mutates\n");
  }

  /// A pass tells the person when the task changed what an existing test expects: a test made to
  /// fit a wrong change catches every mutant, and a task that only weakens a test changes no line
  /// mutastic mutates.
  @Test
  void aPassSaysWhenTheTaskChangedWhatAnExistingTestExpects() throws Exception {
    mutastic("gate", "open", project.toString());
    var test = project.resolve("src/test/java/com/example/pricing/PricingTest.java");
    var original = Files.readString(test);
    Files.writeString(
        test, original.replace("assertEquals(0, pricing.shipping(40));", "pricing.shipping(40);"));
    var weakened = mutastic("gate", "check", project.toString());
    Files.writeString(test, original.replace("assertEquals(25,", "assertEquals(50,"));
    var pricing = project.resolve(PRICING);
    Files.writeString(pricing, Files.readString(pricing).replace("total / 10", "total / 5"));
    var refitted = mutastic("gate", "check", project.toString());
    Files.writeString(test, original + "\n// tried\n");
    git("checkout", "--", PRICING.toString());
    var untouched = mutastic("gate", "check", project.toString());

    for (var passed : java.util.List.of(weakened, refitted)) {
      assertThat(passed.exit()).isEqualTo(Main.PASSED);
      assertThat(passed.out())
          .contains("passed")
          .contains(
              "The task changed what tests that were there when it started expect, in"
                  + " PricingTest.java: check that's what you asked for");
    }
    assertThat(untouched.exit()).isEqualTo(Main.PASSED);
    assertThat(untouched.out()).doesNotContain("changed what tests");
  }

  @Test
  void gateNeedsToBeToldWhatToDo() {
    var bare = mutastic("gate");
    assertThat(bare.exit()).isEqualTo(Main.USAGE);
    assertThat(bare.err()).contains("gate needs what to do: open, check or close");
    assertThat(mutastic("gate", "open", "--help").out()).contains("Usage: mutastic gate open");
  }

  /// A change the agent makes when sent back that doesn't close the gap: a test that checks
  /// nothing.
  private void anotherTry() throws Exception {
    var test = project.resolve("src/test/java/com/example/pricing/PricingTest.java");
    Files.writeString(test, Files.readString(test) + "\n// tried\n");
  }

  /// `discount()` for orders over fifty as well: a change whose boundary no test checks.
  private void aDiscountForOrdersOverFifty() throws Exception {
    var pricing = project.resolve(PRICING);
    Files.writeString(
        pricing,
        Files.readString(pricing)
            .replace("if (total >= 100) {", "if (total >= 100 || total > 50) {"));
  }

  /// A hook runs wherever the agent is, so where mutastic reads no build it says nothing and lets
  /// the agent go on; run by a person, the same command says what's wrong.
  @Test
  void asAHookItPassesQuietlyWhereThereIsNoBuild() throws Exception {
    var notes = Files.createDirectories(work.resolve("notes")).toString();
    var session = "{\"session_id\":\"s1\"}".getBytes(StandardCharsets.UTF_8);
    System.setIn(new ByteArrayInputStream(session));
    var opened = mutastic("gate", "open", notes, "--hook=claude");
    System.setIn(new ByteArrayInputStream(session));
    var checked = mutastic("gate", "check", notes, "--hook=claude");
    var person = mutastic("gate", "check", notes);

    for (var hook : java.util.List.of(opened, checked)) {
      assertThat(hook.exit()).isEqualTo(Main.PASSED);
      assertThat(hook.out()).isEmpty();
      assertThat(hook.err()).isEmpty();
    }
    assertThat(person.exit()).isEqualTo(Main.USAGE);
    assertThat(person.err()).contains("isn't a Gradle or Maven build");
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
