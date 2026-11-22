package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.Operator;
import io.mutastic.api.Policy;
import io.mutastic.api.Rule;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/// `arid: only feeds a log line`, in every shape javac writes: a value worked out only for a log
/// call is arid, inline, wrapped, through a local, a builder or `invokedynamic`, a varargs array, a
/// ternary or a `catch`; one that also reaches a `return`, a comparison, a field, a metric or a
/// method of the code's own stays counted. The fixture's methods say which by their names.
class LoggedValueTruthTest {
  private static final Path FIXTURE = ClaimFixture.ROOT.resolve("logged");

  /// The operators whose mutants change only a value the code works out.
  private static final Set<Operator> CALCULATIONS =
      Set.of(
          BuiltIn.MATH,
          BuiltIn.ARITHMETIC_REPLACEMENT,
          BuiltIn.OTHER_ARITHMETIC,
          BuiltIn.BITWISE,
          BuiltIn.KEEP_OPERAND,
          BuiltIn.REMOVE_NEGATION,
          BuiltIn.CONSTANT_VALUE,
          BuiltIn.REMOVE_ASSIGNMENT);

  /// Whether the mutant changes only a value: one of [#CALCULATIONS], or `Math.min` for `max`.
  private static boolean changesAValue(Mutant mutant) {
    return CALCULATIONS.contains(mutant.operator())
        || mutant.operator().id().equals("library/swap-math");
  }

  @ParameterizedTest
  @CsvSource({
    "8, -g",
    "8, -g:none",
    "11, -g",
    "11, -g:none",
    "17, -g",
    "17, -g:none",
    "21, -g",
    "21, -g:none",
    "25, -g",
    "25, -g:none"
  })
  void aValueOnlyLoggedIsAridAndOneUsedElsewhereIsCounted(String release, String debug)
      throws Exception {
    var found = found(release, debug);

    var only = found.stream().filter(mutant -> mutant.methodName().startsWith("only")).toList();
    assertThat(only.stream().map(Mutant::methodName).distinct()).hasSize(18);
    assertThat(only)
        .filteredOn(LoggedValueTruthTest::changesAValue)
        .filteredOn(mutant -> !mutant.skipped().equals(Optional.of(LoggedValues.REASON)))
        .extracting(LoggedValueTruthTest::shown)
        .isEmpty();

    assertThat(
            only.stream()
                .filter(mutant -> mutant.skipped().equals(Optional.of(LoggedValues.REASON)))
                .map(Mutant::methodName)
                .distinct())
        .as("an arid calculation in each")
        .hasSize(18);
    assertThat(only)
        .as("a Math call swapped")
        .anyMatch(
            mutant ->
                mutant.operator().id().equals("library/swap-math")
                    && mutant.skipped().equals(Optional.of(LoggedValues.REASON)));

    var kept = found.stream().filter(mutant -> mutant.methodName().startsWith("kept")).toList();
    assertThat(kept.stream().map(Mutant::methodName).distinct()).hasSize(10);
    assertThat(kept)
        .filteredOn(mutant -> mutant.skipped().equals(Optional.of(LoggedValues.REASON)))
        .extracting(LoggedValueTruthTest::shown)
        .isEmpty();
    assertThat(
            kept.stream()
                .filter(LoggedValueTruthTest::changesAValue)
                .filter(mutant -> mutant.skipped().isEmpty())
                .map(Mutant::methodName)
                .distinct())
        .as("a counted calculation in each")
        .hasSize(10);
  }

  private static String shown(Mutant mutant) {
    return "%s %s %s %s"
        .formatted(
            mutant.methodName(),
            mutant.operator(),
            mutant.replacement(),
            mutant.skipped().orElse("counted"));
  }

  /// `arid: [mutastic:output]` and `@DoMutate` bring the values back; another rule's list doesn't.
  @Test
  void theOutputRuleBringsThemBack() throws Exception {
    var timed = source();
    var plain = found(timed, Policy.NONE, "25", "-g");
    var logged =
        plain.stream()
            .filter(mutant -> mutant.skipped().equals(Optional.of(LoggedValues.REASON)))
            .map(Mutant::id)
            .collect(Collectors.toSet());
    assertThat(logged).isNotEmpty();

    var output =
        new Rule(true, timed.className(), "the log is our API", List.of("mutastic:output"));
    var waits = new Rule(true, timed.className(), "waits", List.of("mutastic:waits"));
    var annotated =
        new Javac.Source(
            timed.className(),
            timed.text().replaceFirst("(?m)^(class Timed\\b)", "@DoMutate\n$1")
                + "\n@interface DoMutate { String[] arid() default {}; }\n");

    assertThat(skipsOf(found(timed, policy(output), "25", "-g"), logged))
        .containsOnly(Optional.empty());
    assertThat(skipsOf(found(annotated, Policy.NONE, "25", "-g"), logged))
        .containsOnly(Optional.empty());
    assertThat(skipsOf(found(timed, policy(waits), "25", "-g"), logged))
        .containsOnly(Optional.of(LoggedValues.REASON));
  }

  private static List<Optional<String>> skipsOf(List<Mutant> found, Set<String> ids) {
    return found.stream().filter(mutant -> ids.contains(mutant.id())).map(Mutant::skipped).toList();
  }

  private static Policy policy(Rule rule) {
    return new Policy(List.of(rule), List.of());
  }

  private static Javac.Source source() throws IOException {
    return Javac.Source.read(FIXTURE.resolve("Timed.java"), "logged.Timed");
  }

  /// Every mutant of the fixture, every operator's, compiled the way given.
  static List<Mutant> found(String release, String debug) throws Exception {
    return found(source(), Policy.NONE, release, debug);
  }

  private static List<Mutant> found(Javac.Source timed, Policy policy, String release, String debug)
      throws IOException {
    var logger = Javac.Source.read(FIXTURE.resolve("org/slf4j/Logger.java"), "org.slf4j.Logger");
    var classes = Javac.compile(List.of(timed), List.of(logger), "--release", release, debug);
    var model = ClassFile.of().parse(Objects.requireNonNull(classes.get(timed.className())));
    return new Mutations(ClassFile.of(), policy.withOperators(List.of("all"), List.of()))
        .find(model);
  }
}
