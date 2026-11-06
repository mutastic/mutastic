package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import io.mutastic.report.SourceView;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// Each card a swap between similar JDK methods gets says what its mutant does: the fixture writes
/// the swap out beside each method, and the mutant must behave as that source does on every input;
/// the input its help asks for must show the difference; and what's skipped as equivalent must
/// behave as the original does. One compile, as this runs for every mutant that reaches it.
class LibraryTruthTest {
  private static final Path FIXTURE = Path.of("src/test/resources/truth");
  private static final String CLASS = "library.Swaps";
  private static final List<String> TEXTS =
      List.of(
          "a", "b", "x", "aa", "ab", "ba", "aba", "xax", "abab", " a", "a ", " a ", "A", "aB", "1",
          "aaa", "abcde", " ");

  /// An input for each method, as its help describes it.
  private static final Map<String, List<String>> ASKED_FOR =
      Map.of(
          "indexOf", List.of("xax", "x"),
          "lastIndexOf", List.of("xax", "x"),
          "startsWith", List.of("xa", "x"),
          "endsWith", List.of("xa", "x"),
          "toUpperCase", List.of("aB", "x"),
          "toLowerCase", List.of("aB", "x"),
          "stripLeading", List.of(" a", "x"),
          "stripTrailing", List.of(" a", "x"));

  private static ClassModel model;
  private static Class<?> original;

  @BeforeAll
  static void compile() throws Exception {
    var source = Javac.Source.read(FIXTURE.resolve("library/Swaps.java"), CLASS);
    var bytes = Javac.compile(List.of(source), List.of(), "-g").get(CLASS);
    model = ClassFile.of().parse(Objects.requireNonNull(bytes));
    original = load(bytes);
  }

  @Test
  void everySwapsCardSaysWhatItsMutantDoes() throws Exception {
    var mutants = running();
    assertThat(mutants).as("the cards checked").hasSizeGreaterThan(15);

    for (var mutant : mutants) {
      var method = sourceMethod(mutant);
      var mutated = outcomes(mutantClass(mutant), method);
      assertThat(mutated)
          .as(describe(mutant))
          .isEqualTo(outcomes(original, method + "Claim"))
          .isNotEqualTo(outcomes(original, method));
      assertThat(mutated)
          .as("%s calls a method that exists", describe(mutant))
          .noneMatch(outcome -> outcome.contains("threw"));
    }
  }

  @Test
  void aSwapsCardNamesBothMethodsAndTheChange() throws Exception {
    var sources = new SourceView(FIXTURE, List.of(FIXTURE));
    for (var mutant : running()) {
      var advice = sources.advice(mutant);
      var name = CallRule.calledName(mutant.original());
      assertThat(advice.change()).isEqualTo("changed to " + mutant.replacement());
      assertThat(advice.headline())
          .isEqualTo(
              "no test tells `%s` from `%s` in %s()"
                  .formatted(name, mutant.replacement(), mutant.methodName()));
      assertThat(advice.fix()).doesNotContain("{");
    }
  }

  @Test
  void theInputAStringSwapsHelpAsksForShowsTheDifference() throws Exception {
    for (var mutant : running()) {
      var asked = ASKED_FOR.get(CallRule.calledName(mutant.original()));
      if (asked != null) {
        var method = sourceMethod(mutant);
        var inputs = new Object[] {asked.get(0), asked.get(1)};
        assertThat(outcome(mutantClass(mutant), method, inputs))
            .as(describe(mutant))
            .isNotEqualTo(outcome(original, method, inputs));
      }
    }
  }

  @Test
  void onlyTheOverloadsTheOtherMethodHasAreSwapped() throws Exception {
    assertThat(mutants())
        .extracting(Mutant::methodName)
        .doesNotContain(
            "startsWithAtAnOffset", "indexOfFrom", "lastIndexOfFrom", "indexOfBetween", "trimmed");
  }

  @Test
  void everyOverloadARuleSwapsExistsOnBothSides() {
    for (var rule : CallRules.BUILT_IN) {
      if (rule.id().startsWith("library/swap") && !rule.descriptors().isEmpty()) {
        rule.methods()
            .forEach(name -> assertThat(rule.rewrite()).as(rule.id()).contains(name + "->"));
        assertThat(rule.owners()).containsExactly("java.lang.String");
        for (String descriptor : rule.descriptors()) {
          assertOverloadsMatch(rule, descriptor);
        }
      }
    }
  }

  private static void assertOverloadsMatch(CallRule rule, String descriptor) {
    for (String pair : rule.rewrite().substring("rename ".length()).split(",\\s*", -1)) {
      var names = pair.split("->", 2);
      assertThat(hasOverload(names[0], descriptor))
          .as("%s and %s %s", names[0], names[1], descriptor)
          .isEqualTo(hasOverload(names[1], descriptor));
    }
  }

  private static boolean hasOverload(String name, String descriptor) {
    return java.util.Arrays.stream(String.class.getMethods())
        .anyMatch(
            method ->
                method.getName().equals(name)
                    && java.lang.invoke.MethodType.methodType(
                            method.getReturnType(), method.getParameterTypes())
                        .toMethodDescriptorString()
                        .equals(descriptor));
  }

  @Test
  void aTestOfWhetherItsThereIsSkippedAsEquivalent() throws Exception {
    var why = "equivalent: `%s` and `%s` agree on whether it's there";
    assertThat(skips("isThere")).containsExactly(why.formatted("indexOf", "lastIndexOf"));
    assertThat(skips("isNotThere")).containsExactly(why.formatted("lastIndexOf", "indexOf"));
    assertThat(skips("isMinusOne")).containsExactly(why.formatted("indexOf", "lastIndexOf"));
    assertThat(skips("isNotMinusOne")).containsExactly(why.formatted("indexOf", "lastIndexOf"));
    assertThat(skips("overMinusOne")).containsExactly(why.formatted("lastIndexOf", "indexOf"));
    assertThat(skips("atMostMinusOne")).containsExactly(why.formatted("indexOf", "lastIndexOf"));
  }

  @Test
  void aWholeNumberBeingRoundedIsSkippedAsEquivalent() throws Exception {
    var why = "equivalent: a whole number rounds to itself with `%s` and `%s`";
    assertThat(skips("floorOfAnInt")).containsExactly(why.formatted("floor", "ceil"));
    assertThat(skips("ceilOfADivision")).containsExactly(why.formatted("ceil", "floor"));
    assertThat(skips("floorOfALong")).containsExactly(why.formatted("floor", "ceil"));
  }

  @Test
  void whatIsSkippedAsEquivalentBehavesAsTheOriginal() throws Exception {
    var skipped = mutants().stream().filter(mutant -> mutant.skipped().isPresent()).toList();
    assertThat(skipped).as("the swaps skipped").hasSize(9);

    for (var mutant : skipped) {
      var method = sourceMethod(mutant);
      assertThat(outcomes(mutantClass(mutant), method))
          .as(describe(mutant))
          .isEqualTo(outcomes(original, method));
    }
  }

  private static List<String> skips(String method) {
    return mutants().stream()
        .filter(mutant -> mutant.methodName().equals(method))
        .map(mutant -> mutant.skipped().orElse("runs"))
        .toList();
  }

  private static List<Mutant> running() {
    return mutants().stream().filter(mutant -> mutant.skipped().isEmpty()).toList();
  }

  private static List<Mutant> mutants() {
    return mutations().find(model).stream()
        .filter(mutant -> !sourceMethod(mutant).endsWith("Claim"))
        .toList();
  }

  private static Mutations mutations() {
    return new Mutations(
        ClassFile.of(),
        Policy.NONE.withOperators(
            List.of("library/swap-string", "library/swap-index-of", "library/swap-math"),
            List.of()));
  }

  /// `inALambda`, for a mutant in `lambda$inALambda$0`.
  private static String sourceMethod(Mutant mutant) {
    var name = mutant.methodName();
    return name.startsWith("lambda$") ? name.substring(7, name.lastIndexOf('$')) : name;
  }

  private static Class<?> mutantClass(Mutant mutant) throws ClassNotFoundException {
    return load(mutations().apply(model, mutant));
  }

  private static List<String> outcomes(Class<?> owner, String method)
      throws ReflectiveOperationException {
    var outcomes = new ArrayList<String>();
    for (String a : TEXTS) {
      for (String b : TEXTS) {
        outcomes.add(a + "," + b + ": " + outcome(owner, method, new Object[] {a, b}));
      }
    }
    return outcomes;
  }

  private static String outcome(Class<?> owner, String name, Object[] inputs)
      throws ReflectiveOperationException {
    var method = owner.getDeclaredMethod(name, String.class, String.class);
    method.setAccessible(true);
    try {
      return String.valueOf(method.invoke(null, inputs));
    } catch (InvocationTargetException thrown) {
      return "threw " + thrown.getCause().getClass().getSimpleName();
    }
  }

  private static String describe(Mutant mutant) {
    return "%s line %d %s".formatted(mutant.methodName(), mutant.line(), mutant.replacement());
  }

  private static Class<?> load(byte[] bytes) throws ClassNotFoundException {
    return Javac.loader(Map.of(CLASS, bytes)).loadClass(CLASS);
  }
}
