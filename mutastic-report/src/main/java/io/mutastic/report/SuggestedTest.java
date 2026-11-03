package io.mutastic.report;

import io.mutastic.api.MutantResult;
import io.mutastic.api.Suggestion;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// A suggested test as code to paste: into the test class of the fastest test that already reaches
/// the mutant, written the way that class writes assertions (AssertJ's `assertThat` if it imports
/// AssertJ, JUnit's otherwise), with a name it doesn't already use.
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
    var fastest = result.coveredBy().getFirst();
    var testClass = fastest.contains("#") ? fastest.substring(0, fastest.indexOf('#')) : fastest;
    var source = sources.testSource(testClass);
    var style = Style.of(source.orElse(""));
    var name = freeName(name(result.mutant().methodName(), suggestion), source.orElse(""));
    var lines = new ArrayList<String>();
    lines.add("@Test");
    lines.add((style == Style.TESTNG ? "public void %s() {" : "void %s() {").formatted(name));
    lines.add("  " + assertion(suggestion, style) + " // " + outcomes(suggestion));
    lines.add("}");
    return Optional.of(
        new SuggestedTest(
            testClass.substring(testClass.lastIndexOf('.') + 1),
            name,
            lines,
            outcomes(suggestion)));
  }

  /// The tests suggested for the mutants of `finding`, each call once: two mutants on one side of a
  /// condition get the same call, and pasting it twice wouldn't compile.
  static List<SuggestedTest> distinct(Finding finding, SourceView sources) {
    var byName = new java.util.LinkedHashMap<String, SuggestedTest>();
    for (MutantResult result : finding.mutants()) {
      of(result, sources)
          .ifPresent(test -> byName.putIfAbsent(test.testClass() + "#" + test.name(), test));
    }
    return List.copyOf(byName.values());
  }

  /// For mutants no test reaches, an assertion to start each method's first test with, at most
  /// `limit`, in the style of the class's own test class (`PricingTest`) if it has one.
  static List<String> startingPoints(List<MutantResult> uncovered, SourceView sources, int limit) {
    return uncovered.stream()
        .flatMap(
            result ->
                result.suggestion().stream()
                    .map(
                        suggestion ->
                            assertion(
                                suggestion,
                                Style.of(
                                    sources
                                        .testSource(result.mutant().binaryName() + "Test")
                                        .orElse("")))))
        .distinct()
        .limit(limit)
        .toList();
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

  /// `discountWithTotal100`, `feeWithAmountMinus1`; `sizeResult` with no arguments; and the
  /// receiver's arguments too when it has some, `openOnWithDay5ForWindow5And10`, or an inner
  /// class's outer instance's, `heavyResultForParcel30InShipping10`. Every argument is in the name,
  /// so two different calls never share one, and the same call (for two mutants) is the same test.
  static String name(String method, Suggestion suggestion) {
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

  /// `For` the receiver's type and its arguments, `ForWindow5And10`, then `In` each instance it's
  /// made in that takes arguments, innermost first: `ForParcel30InShipping10`.
  private static void appendReceiver(StringBuilder name, List<Suggestion.Instance> receiver) {
    for (int index = receiver.size() - 1; index >= 0; index--) {
      var instance = receiver.get(index);
      boolean innermost = index == receiver.size() - 1;
      if (innermost || !instance.arguments().isEmpty()) {
        name.append(innermost ? "For" : "In").append(instance.type());
        appendArguments(name, instance.arguments());
      }
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
