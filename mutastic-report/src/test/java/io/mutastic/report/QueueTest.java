package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Operator;
import io.mutastic.api.Status;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `mutastic next`: the missed mutants as a queue in the run's order, new before the baseline's,
/// `--top` spread over the code, and each item saying why it's where it is.
class QueueTest {
  @TempDir Path project;

  private SourceView sources;

  @BeforeEach
  void source() throws Exception {
    var file = project.resolve("src/main/java/com/example/Pricing.java");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        """
        package com.example;

        public class Pricing {
          public int discount(int total) {
            if (total >= 100) {
              return total / 10;
            }
            return 0;
          }

          public int shipping(int total) {
            return total + 5;
          }

          public int tax(int total) {
            return total * 2;
          }
        }
        """);
    sources = new SourceView(project, List.of(project.resolve("src/main/java")));
  }

  @Test
  void aConditionComesFirstThenTheCodeMoreTestsRunThenTheBaselines() {
    var arithmetic = missed(mutant("shipping", 12, BuiltIn.MATH), 5);
    var fewer = missed(mutant("discount", 5, BuiltIn.BOUNDARY), 1);
    var more = missed(mutant("tax", 16, BuiltIn.RETURN_VALUE), 3);
    var legacy = missed(mutant("discount", 6, BuiltIn.RETURN_VALUE), 9);

    var queue =
        Queue.of(
            AgentTextTest.report(List.of(arithmetic, fewer, more, legacy), List.of(), List.of()),
            Set.of(legacy.mutant().id()));

    assertThat(Queue.leads(queue.findings())).containsExactly(more, fewer, arithmetic, legacy);
    assertThat(queue.factors(queue.findings().getFirst()))
        .extracting(Queue.Factor::why)
        .containsExactly(
            "not in the baseline: a new miss",
            "a condition or a removal: it changes what the code decides or does",
            "3 tests run it, and none checks it");
  }

  /// `--top` shows at most one item a method and two a file, so one hot method can't fill it.
  @Test
  void theTopIsSpreadOverMethodsAndFiles() {
    var first = missed(mutant("discount", 5, BuiltIn.BOUNDARY), 4);
    var sameMethod = missed(mutant("discount", 6, BuiltIn.RETURN_VALUE), 3);
    var other = missed(mutant("shipping", 12, BuiltIn.RETURN_VALUE), 2);
    var thirdInFile = missed(mutant("tax", 16, BuiltIn.RETURN_VALUE), 1);

    var queue =
        Queue.of(
            AgentTextTest.report(
                List.of(first, sameMethod, other, thirdInFile), List.of(), List.of()),
            Set.of());

    assertThat(Queue.leads(queue.top(5))).containsExactly(first, other);
    assertThat(Queue.leads(queue.top(1))).containsExactly(first);
  }

  /// For an agent, one task, whole: why it's first, the rules, the test, how it's done, how to
  /// decline, and to ask for the next one. For people, each card with why it's ranked there.
  @Test
  void anAgentGetsOneTaskAndAPersonEachCardWithWhyItsThere() {
    var head = missed(mutant("discount", 5, BuiltIn.BOUNDARY), 2);
    var second = missed(mutant("shipping", 12, BuiltIn.MATH), 1);
    var queue =
        Queue.of(AgentTextTest.report(List.of(head, second), List.of(), List.of()), Set.of());
    var text = text();

    var agent = text.agent(queue, "pricing");
    var human = text.human(queue, "pricing", 5);
    var empty = Queue.of(AgentTextTest.report(List.of(), List.of(), List.of()), Set.of());

    assertThat(agent)
        .startsWith("mutastic next: 1 of 2 lines with missed mutants in pricing; close this one")
        .contains("why first: a condition or a removal")
        .contains("never delete, disable or loosen a test")
        .contains("decline: ")
        .contains("missed " + head.mutant().id())
        .contains("done when: mutastic verify " + head.mutant().id())
        .contains("then: run `mutastic next` again")
        .doesNotContain(second.mutant().id());
    assertThat(human)
        .startsWith("Next: the first 2 of 2 lines with missed mutants in pricing")
        .contains("Ranked first because\n   a condition or a removal")
        .contains("Ranked 2nd because\n   arithmetic or another change")
        .contains("Then: mutastic next, for the next one");
    assertThat(text.agent(empty, "pricing"))
        .isEqualTo("mutastic next: nothing missed is left in pricing\n");
    assertThat(NextText.ordinal(3)).isEqualTo("3rd");
    assertThat(NextText.ordinal(11)).isEqualTo("11th");
    assertThat(NextText.ordinal(22)).isEqualTo("22nd");
  }

  /// For tools, an event for each item shown, its lead in full with where it stands and why, and
  /// one saying how many are queued: each as the published schema describes it.
  @Test
  void eachItemIsAnEventWithItsRankAndTheSchemaDescribesIt() {
    var head = missed(mutant("discount", 5, BuiltIn.BOUNDARY), 2);
    var legacy = missed(mutant("shipping", 12, BuiltIn.MATH), 1);
    var queue =
        Queue.of(
            AgentTextTest.report(List.of(head, legacy), List.of(), List.of()),
            Set.of(legacy.mutant().id()));
    var bytes = new java.io.ByteArrayOutputStream();

    new JsonEvents(new java.io.PrintStream(bytes, true, StandardCharsets.UTF_8), sources, 80)
        .next(queue, 5);
    var events = bytes.toString(StandardCharsets.UTF_8);

    Schemas.checkEvents(events);
    assertThat(events.lines().toList())
        .hasSize(3)
        .first()
        .asString()
        .contains("\"reason\":\"next-item\"")
        .contains("\"ids\":[\"" + head.mutant().id() + "\"]")
        .contains(
            "\"rank\":{\"position\":1,\"order\":\"run-1\",\"factors\":[{\"signal\":\"baseline\","
                + "\"value\":\"new\"");
    assertThat(events.lines().toList().getLast())
        .contains(
            "\"reason\":\"next-finished\",\"queued\":2,\"mutants\":2,\"shown\":2,\"legacy\":1");
  }

  private NextText text() {
    var human = new HumanText(sources, 80, Style.PLAIN, HumanText.Detail.FULL, Vocabulary.PLAIN);
    return new NextText(sources, human, Style.PLAIN, Vocabulary.PLAIN);
  }

  private static Mutant mutant(String method, int line, Operator operator) {
    var opcodes =
        operator == BuiltIn.BOUNDARY
            ? List.of("IF_ICMPLT", "IF_ICMPLE")
            : operator == BuiltIn.MATH ? List.of("IADD", "ISUB") : List.of("IRETURN", "return 0");
    return new Mutant(
        "com/example/Pricing",
        method,
        "(I)I",
        line,
        line,
        operator,
        opcodes.get(0),
        opcodes.get(1),
        Optional.empty(),
        List.of(),
        List.of("total", "100"));
  }

  private static MutantResult missed(Mutant mutant, int covering) {
    var tests =
        java.util.stream.IntStream.range(0, covering)
            .mapToObj(test -> "com.example.PricingTest#t" + test)
            .toList();
    return new MutantResult(
        mutant,
        Status.MISSED,
        tests,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        covering,
        Optional.empty(),
        Map.of(),
        Optional.empty(),
        Duration.ofMillis(3));
  }
}
