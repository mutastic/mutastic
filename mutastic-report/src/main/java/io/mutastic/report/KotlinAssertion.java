package io.mutastic.report;

import io.mutastic.api.Suggestion;
import java.util.Optional;
import java.util.Set;

/// A suggested test's assertion in Kotlin: no semicolon, a lambda for what should throw,
/// `::class.java` for its type, and a `$` in a string kept from starting a template.
final class KotlinAssertion {
  /// What Java's `String.valueOf` writes that Kotlin can't read back as a value.
  private static final Set<String> NOT_LITERALS =
      Set.of("NaN", "Infinity", "-Infinity", "NaNf", "Infinityf", "-Infinityf");

  private KotlinAssertion() {}

  /// `assertEquals(10, discount(100))`, or the AssertJ or TestNG equivalent; empty when today's
  /// value can't be written.
  static Optional<String> of(Suggestion suggestion, SuggestedTest.Style style) {
    var today = suggestion.today();
    if (NOT_LITERALS.contains(today)) {
      return Optional.empty();
    }
    var call = suggestion.call();
    if (!suggestion.returnsValue()) {
      return Optional.of(throwing(call, today.substring("throws ".length()), style));
    }
    boolean assertJ = style == SuggestedTest.Style.ASSERTJ;
    if (today.equals("true") || today.equals("false")) {
      var truth = today.equals("true") ? "True" : "False";
      return Optional.of(
          assertJ
              ? "assertThat(%s).is%s()".formatted(call, truth)
              : "assert%s(%s)".formatted(truth, call));
    }
    if (today.equals("null")) {
      return Optional.of(
          assertJ ? "assertThat(%s).isNull()".formatted(call) : "assertNull(%s)".formatted(call));
    }
    return Optional.of(equal(call, literal(today), style));
  }

  private static String throwing(String call, String type, SuggestedTest.Style style) {
    return style == SuggestedTest.Style.ASSERTJ
        ? "assertThatThrownBy { %s }.isInstanceOf(%s::class.java)".formatted(call, type)
        : "assertThrows(%s::class.java) { %s }".formatted(type, call);
  }

  private static String equal(String call, String today, SuggestedTest.Style style) {
    return switch (style) {
      case ASSERTJ -> "assertThat(%s).isEqualTo(%s)".formatted(call, today);
      case TESTNG -> "assertEquals(%s, %s)".formatted(call, today);
      case JUNIT -> "assertEquals(%s, %s)".formatted(today, call);
    };
  }

  /// A string's `$` escaped: `"$name"` is a template in Kotlin, and a string in Java.
  private static String literal(String today) {
    return today.startsWith("\"") ? today.replace("$", "\\$") : today;
  }
}
