package io.mutastic.report;

import io.mutastic.api.MutantResult;
import io.mutastic.api.Suggestion;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// A suggested test as code to paste: into the test class of the first test listed as reaching the
/// mutant (its own test class, if a test there does), written the way that class writes assertions
/// (AssertJ's `assertThat` if it imports AssertJ, JUnit's otherwise), with a name it doesn't
/// already use.
///
/// @param testClass where it goes, `PricingTest`
/// @param lines the test method, one line each, unindented
/// @param outcomes `today 10 · with this mutant 0`
public record SuggestedTest(String testClass, String name, List<String> lines, String outcomes) {

  public SuggestedTest {
    lines = List.copyOf(lines);
  }

  static Optional<SuggestedTest> of(MutantResult result, SourceView sources) {
    if (result.suggestion().isEmpty() || result.coveredBy().isEmpty()) {
      return Optional.empty();
    }
    var suggestion = result.suggestion().get();
    var first = result.coveredBy().getFirst();
    var testClass = first.contains("#") ? first.substring(0, first.indexOf('#')) : first;
    if (sources.isKotlin(result.mutant())) {
      return inKotlin(result, testClass, sources);
    }
    var source = sources.testSource(testClass);
    var style = Style.of(source.orElse(""));
    var name = freeName(name(result.mutant().methodName(), suggestion), source.orElse(""));
    return Optional.of(
        new SuggestedTest(
            testClass.substring(testClass.lastIndexOf('.') + 1),
            name,
            lines(suggestion, style, name),
            outcomes(suggestion)));
  }

  /// The test method, a line each: with the local a value the source names is declared in, if it
  /// has one.
  private static List<String> lines(Suggestion suggestion, Style style, String name) {
    var lines = new ArrayList<String>();
    lines.add("@Test");
    lines.add((style == Style.TESTNG ? "public void %s() {" : "void %s() {").formatted(name));
    suggestion.named().flatMap(Suggestion.Named::local).ifPresent(local -> lines.add("  " + local));
    lines.add("  " + assertion(suggestion, style) + " // " + comment(suggestion));
    lines.add("}");
    return lines;
  }

  /// The test in Kotlin, for what kotlinc compiled. A call written in Kotlin can't be pasted into a
  /// test class in Java, so there's none for one.
  private static Optional<SuggestedTest> inKotlin(
      MutantResult result, String testClass, SourceView sources) {
    if (sources.testFile(testClass).filter(file -> file.endsWith(".java")).isPresent()) {
      return Optional.empty();
    }
    var suggestion = result.suggestion().orElseThrow();
    var source = sources.testSource(testClass).orElse("");
    var name = freeName(name(result.mutant().methodName(), suggestion), source);
    return KotlinAssertion.of(suggestion, Style.ofKotlin(source))
        .map(
            assertion ->
                new SuggestedTest(
                    testClass.substring(testClass.lastIndexOf('.') + 1),
                    name,
                    List.of(
                        "@Test",
                        "fun %s() {".formatted(name),
                        "    " + assertion + " // " + outcomes(suggestion),
                        "}"),
                    outcomes(suggestion)));
  }

  /// The tests suggested for the mutants of `finding`, each call once: two mutants on one side of a
  /// condition get the same call, and pasting it twice wouldn't compile.
  static List<SuggestedTest> distinct(Finding finding, SourceView sources) {
    var byName = new java.util.LinkedHashMap<String, SuggestedTest>();
    boolean shared = finding.mutants().size() > 1;
    for (MutantResult result : finding.mutants()) {
      of(result, sources)
          .map(test -> shared ? test.naming(result.mutant().id()) : test)
          .ifPresent(test -> byName.putIfAbsent(test.testClass() + "#" + test.name(), test));
    }
    return List.copyOf(byName.values());
  }

  /// The same test, its comment naming the mutant it was found for, `with md4db94d8 true`: on a
  /// card with others, "this mutant" could be any of them.
  SuggestedTest naming(String id) {
    var named = "with " + id + " ";
    return new SuggestedTest(
        testClass,
        name,
        lines.stream().map(line -> line.replace("with this mutant ", named)).toList(),
        outcomes.replace("with this mutant ", named));
  }

  /// For mutants no test reaches, an assertion to start each method's first test with, at most
  /// `limit`, in the style of the class's own test class (`PricingTest`) if it has one.
  static List<String> startingPoints(List<MutantResult> uncovered, SourceView sources, int limit) {
    return uncovered.stream()
        .flatMap(
            result ->
                result.suggestion().stream()
                    .flatMap(suggestion -> startingPoint(result, suggestion, sources).stream()))
        .distinct()
        .limit(limit)
        .toList();
  }

  private static Optional<String> startingPoint(
      MutantResult result, Suggestion suggestion, SourceView sources) {
    var source = sources.testSource(result.mutant().binaryName() + "Test").orElse("");
    return sources.isKotlin(result.mutant())
        ? KotlinAssertion.of(suggestion, Style.ofKotlin(source))
        : Optional.of(assertion(suggestion, Style.of(source)));
  }

  /// How the test class writes its tests: JUnit's assertions, AssertJ's, or TestNG's, whose test
  /// methods must be public and whose `assertEquals` takes the actual value first.
  enum Style {
    JUNIT,
    ASSERTJ,
    TESTNG;

    /// The style of a test class's source, by what it imports.
    static Style of(String source) {
      if (source.contains("import static org.assertj.")) {
        return ASSERTJ;
      }
      return source.contains("import static org.testng.") || source.contains("import org.testng.")
          ? TESTNG
          : JUNIT;
    }

    /// The style of a test class in Kotlin, whose imports have no `static`.
    static Style ofKotlin(String source) {
      if (source.contains("import org.assertj.")) {
        return ASSERTJ;
      }
      return source.contains("import org.testng.") ? TESTNG : JUNIT;
    }
  }

  /// `assertEquals(10, new Pricing().discount(100));`, or the AssertJ or TestNG equivalent.
  static String assertion(Suggestion suggestion, Style style) {
    boolean assertJ = style == Style.ASSERTJ;
    var call = suggestion.call();
    var today = suggestion.today();
    if (!suggestion.returnsValue()) {
      var thrown = today.substring("throws ".length());
      return assertJ
          ? "assertThatThrownBy(() -> %s).isInstanceOf(%s.class);".formatted(call, thrown)
          : "assertThrows(%s.class, () -> %s);".formatted(thrown, call);
    }
    if (today.equals("true") || today.equals("false")) {
      return booleanAssertion(call, today, assertJ);
    }
    if (today.equals("null")) {
      return assertJ
          ? "assertThat(%s).isNull();".formatted(call)
          : "assertNull(%s);".formatted(call);
    }
    return switch (style) {
      case ASSERTJ -> "assertThat(%s).isEqualTo(%s);".formatted(call, today);
      case TESTNG -> "assertEquals(%s, %s);".formatted(call, today);
      case JUNIT -> "assertEquals(%s, %s);".formatted(today, call);
    };
  }

  private static String booleanAssertion(String call, String today, boolean assertJ) {
    var truth = today.equals("true") ? "True" : "False";
    return assertJ
        ? "assertThat(%s).is%s();".formatted(call, truth)
        : "assert%s(%s);".formatted(truth, call);
  }

  static String outcomes(Suggestion suggestion) {
    return "today %s · with this mutant %s".formatted(suggestion.today(), suggestion.mutated());
  }

  /// The test's comment: what the value it's named after is, if it says, then the outcomes.
  static String comment(Suggestion suggestion) {
    return suggestion
        .named()
        .map(Suggestion.Named::note)
        .filter(note -> !note.isEmpty())
        .map(note -> note + " · " + outcomes(suggestion))
        .orElseGet(() -> outcomes(suggestion));
  }

  /// `discountWithTotal100`, `feeWithAmountMinus1`; `sizeResult` with no arguments; and the
  /// receiver's arguments too when it has some, `openOnWithDay5ForWindow5And10`, or an inner
  /// class's outer instance's, `heavyResultForParcel30InShipping10`. Every argument is in the name,
  /// so two different calls never share one, and the same call (for two mutants) is the same test.
  static String name(String method, Suggestion suggestion) {
    if (suggestion.named().isPresent()) {
      return method + "At" + pascal(suggestion.named().get().name());
    }
    var name = new StringBuilder(method);
    if (suggestion.inputs().isEmpty()) {
      name.append("Result");
    } else {
      name.append("With");
    }
    for (int index = 0; index < suggestion.inputs().size(); index++) {
      if (index > 0) {
        name.append("And");
      }
      var parameter = suggestion.parameters().get(index);
      long value = suggestion.inputs().get(index);
      name.append(Character.toUpperCase(parameter.charAt(0)))
          .append(parameter.substring(1))
          .append(number(value));
    }
    if (suggestion.receiverTakesArguments()) {
      appendReceiver(name, suggestion.receiver());
    }
    return name.toString();
  }

  /// `FreeFrom` for `FREE_FROM` or `freeFrom`.
  static String pascal(String name) {
    if (!name.equals(name.toUpperCase(java.util.Locale.ROOT))) {
      return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
    var out = new StringBuilder();
    for (var word : name.toLowerCase(java.util.Locale.ROOT).split("_", -1)) {
      if (!word.isEmpty()) {
        out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
      }
    }
    return out.toString();
  }

  /// `For` the receiver's arguments by the constructor's names for them, `ForStart5AndEnd10`, or
  /// its type and arguments when the names aren't known, `ForWindow5And10`; then `In` each instance
  /// it's made in that takes arguments, innermost first: `ForWeight30InRate10`.
  private static void appendReceiver(StringBuilder name, List<Suggestion.Instance> receiver) {
    for (int index = receiver.size() - 1; index >= 0; index--) {
      var instance = receiver.get(index);
      boolean innermost = index == receiver.size() - 1;
      if (innermost || !instance.arguments().isEmpty()) {
        name.append(innermost ? "For" : "In");
        if (instance.parameters().size() == instance.arguments().size()
            && !instance.arguments().isEmpty()) {
          appendNamed(name, instance.parameters(), instance.arguments());
        } else {
          name.append(instance.type());
          appendArguments(name, instance.arguments());
        }
      }
    }
  }

  /// `Start5AndEnd10`.
  private static void appendNamed(StringBuilder name, List<String> parameters, List<Long> values) {
    for (int index = 0; index < values.size(); index++) {
      name.append(index == 0 ? "" : "And")
          .append(pascal(parameters.get(index)))
          .append(number(values.get(index)));
    }
  }

  /// `5And10`.
  private static void appendArguments(StringBuilder name, List<Long> arguments) {
    for (int index = 0; index < arguments.size(); index++) {
      name.append(index == 0 ? "" : "And").append(number(arguments.get(index)));
    }
  }

  private static String number(long value) {
    return value < 0 ? "Minus" + -value : String.valueOf(value);
  }

  /// `name`, or `name2`, `name3`… if the test class already has a method called that.
  private static String freeName(String name, String source) {
    var free = name;
    for (int count = 2; source.matches("(?s).*\\b" + free + "\\s*\\(.*"); count++) {
      free = name + count;
    }
    return free;
  }
}
