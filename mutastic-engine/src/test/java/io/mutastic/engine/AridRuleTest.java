package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.AridRule;
import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// Each arid rule's example, as the docs show it, is skipped by that rule, and every arid reason
/// mutastic gives is one of a rule's.
class AridRuleTest {
  private static final String FIXTURE = "arid/Examples.java";

  /// The fixtures the arid skips' own tests use, with more of their reasons' shapes.
  private static final List<String> MORE =
      List.of(
          "arithmetic/ConstantValues.java",
          "exceptions/Throws.java",
          "discards/Discards.java",
          "text/Strings.java",
          "threads/Settings.java");

  private static List<String> lines = List.of();
  private static List<Mutant> mutants = List.of();

  @BeforeAll
  static void compile() throws Exception {
    lines = Files.readAllLines(ClaimFixture.ROOT.resolve(FIXTURE));
    mutants = found(FIXTURE);
  }

  /// Every mutant of the fixture's class, every operator's.
  private static List<Mutant> found(String file) throws java.io.IOException {
    var className = file.replace(".java", "").replace('/', '.');
    var source = Javac.Source.read(ClaimFixture.ROOT.resolve(file), className);
    var classes = Javac.compile(List.of(source), List.of(), "-g");
    var mutations =
        new Mutations(ClassFile.of(), Policy.NONE.withOperators(List.of("all"), List.of()));
    var model = ClassFile.of().parse(Objects.requireNonNull(classes.get(className)));
    mutations.prepare(List.of(model));
    return mutations.find(model);
  }

  @Test
  void eachRulesExampleIsSkippedByIt() {
    for (var rule : AridRule.values()) {
      int line = lineOf(rule.example());

      assertThat(
              mutants.stream()
                  .filter(mutant -> mutant.line() == line)
                  .map(mutant -> mutant.skipped().flatMap(AridRule::of)))
          .as("%s: %s", rule.id(), rule.example())
          .contains(Optional.of(rule));
    }
  }

  @Test
  void everyAridReasonIsARules() throws Exception {
    var all = new java.util.ArrayList<>(mutants);
    for (var file : MORE) {
      all.addAll(found(file));
    }

    assertThat(
            all.stream()
                .flatMap(mutant -> mutant.skipped().stream())
                .filter(why -> why.startsWith("arid:"))
                .distinct())
        .hasSizeGreaterThan(AridRule.values().length)
        .allSatisfy(why -> assertThat(AridRule.of(why)).as(why).isPresent());
  }

  @Test
  void aReasonWithANameInItIsItsRules() {
    assertThat(AridRule.of("arid: trivial getter of total")).contains(AridRule.TRIVIAL_ACCESSORS);
    assertThat(AridRule.of("arid: `lambda$toString$0()` only serves `toString()`"))
        .contains(AridRule.DEBUG_REPRESENTATIONS);
    assertThat(AridRule.of("arid: a lambda in `hashCode()`"))
        .contains(AridRule.DEBUG_REPRESENTATIONS);
    assertThat(AridRule.of("arid: only waits")).contains(AridRule.WAITS);
    assertThat(AridRule.of("arid: only waits a bit")).isEmpty();
    assertThat(AridRule.of("user: only waits")).isEmpty();
    assertThat(AridRule.of("arid: trivial getter of two words")).isEmpty();
  }

  @Test
  void eachIdIsNamespacedAndOnce() {
    assertThat(AridRule.ids())
        .doesNotHaveDuplicates()
        .allMatch(id -> id.matches("mutastic:[a-z]+(-[a-z]+)*"));
    assertThat(Arrays.stream(AridRule.values()).flatMap(rule -> rule.reasons().stream()))
        .doesNotHaveDuplicates()
        .allMatch(reason -> reason.startsWith("arid: "));
    assertThat(AridRule.WAITS.url("https://docs.mutastic.io/"))
        .isEqualTo("https://docs.mutastic.io/reference/arid/#waits");
    assertThat(AridRule.named("mutastic:capacity")).contains(AridRule.CAPACITY);
    assertThat(AridRule.named("capacity")).isEmpty();
  }

  /// The fixture's line, counted from 1, that holds `text`: only one does.
  private static int lineOf(String text) {
    var at =
        java.util.stream.IntStream.range(0, lines.size())
            .filter(index -> lines.get(index).contains(text))
            .boxed()
            .toList();
    assertThat(at).as("the fixture's lines with %s", text).hasSize(1);
    return at.getFirst() + 1;
  }
}
