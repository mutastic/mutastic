package io.mutastic.report;

import io.mutastic.api.MutantResult;
import io.mutastic.api.Suggestion;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// A suggested test as code to paste: into the test class of the first test listed as reaching the
/// mutant (its own test class, if a test there does), written the way that class writes assertions
/// (AssertJ's `assertThat` if it imports AssertJ, JUnit's otherwise), with a name it doesn't
/// already use. It compiles as pasted: a JUnit 4 or TestNG test is `public`, and what the class
/// doesn't import, as a class of only parameterized tests doesn't import `@Test`, is written in
/// full, `@org.junit.jupiter.api.Test`.
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
    var source = sourceOf(testClass, sources);
    var name = freeName(name(result.mutant().methodName(), suggestion, compared(result)), source);
    return Optional.of(
        new SuggestedTest(
            testClass.substring(testClass.lastIndexOf('.') + 1),
            name,
            lines(suggestion, source, name),
            outcomes(suggestion)));
  }

  /// The test method, a line each: with the local a value the source names is declared in, if it
  /// has one.
  private static List<String> lines(Suggestion suggestion, String source, String name) {
    var framework = Framework.of(source);
    var style = Style.of(source);
    var lines = new ArrayList<String>();
    lines.add("@" + Imports.annotation(source, framework.test(), false));
    lines.add((framework.isPublic() ? "public void %s() {" : "void %s() {").formatted(name));
    suggestion.named().flatMap(Suggestion.Named::local).ifPresent(local -> lines.add("  " + local));
    var made = madeAsATestMakesIt(suggestion);
    made.ifPresent(receiver -> lines.add("  " + receiver.declaration()));
    var called = made.map(MadeAsATest::calledOnIt).orElse(suggestion);
    var assertion = Imports.assertion(source, assertion(called, style), style.assertions(), false);
    lines.add("  " + assertion + " // " + comment(suggestion));
    lines.add("}");
    return lines;
  }

  /// A receiver made as a covering test makes it: declared as that test does, `var delivery = new
  /// Delivery(50); // as DeliveryTest#smallOrders builds it`, so the reader knows where its values
  /// come from.
  ///
  /// @param declaration the line that makes it
  /// @param calledOnIt the suggestion with its call made on the local
  private record MadeAsATest(String declaration, Suggestion calledOnIt) {}

  private static Optional<MadeAsATest> madeAsATestMakesIt(Suggestion suggestion) {
    if (suggestion.builtBy().isEmpty() || suggestion.receiver().size() != 1) {
      return Optional.empty();
    }
    var call = suggestion.call();
    var type = suggestion.receiver().getFirst().type();
    var dot = call.indexOf(").") + 1;
    if (!call.startsWith("new " + type + "(") || dot <= 0) {
      return Optional.empty();
    }
    var local = Character.toLowerCase(type.charAt(0)) + type.substring(1);
    var made = call.substring(0, dot);
    return Optional.of(
        new MadeAsATest(
            "var %s = %s; // as %s builds it".formatted(local, made, suggestion.builtBy().get()),
            new Suggestion(
                local + call.substring(dot),
                suggestion.parameters(),
                suggestion.inputs(),
                suggestion.receiver(),
                suggestion.today(),
                suggestion.mutated(),
                suggestion.named(),
                suggestion.builtBy())));
  }

  /// The source of the file a test class is in: its outer class's, for a nested one,
  /// `ShippingTest$Parcels`. Empty when there's none to read.
  private static String sourceOf(String testClass, SourceView sources) {
    int dollar = testClass.indexOf('$');
    return sources.testSource(dollar < 0 ? testClass : testClass.substring(0, dollar)).orElse("");
  }

  /// The test in Kotlin, for what kotlinc compiled. A call written in Kotlin can't be pasted into a
  /// test class in Java, so there's none for one.
  private static Optional<SuggestedTest> inKotlin(
      MutantResult result, String testClass, SourceView sources) {
    if (sources.testFile(testClass).filter(file -> file.endsWith(".java")).isPresent()) {
      return Optional.empty();
    }
    var suggestion = result.suggestion().orElseThrow();
    var source = sourceOf(testClass, sources);
    var name = freeName(name(result.mutant().methodName(), suggestion, compared(result)), source);
    var style = Style.ofKotlin(source);
    var test = Framework.ofKotlin(source).test();
    return KotlinAssertion.of(suggestion, style)
        .map(
            assertion ->
                new SuggestedTest(
                    testClass.substring(testClass.lastIndexOf('.') + 1),
                    name,
                    List.of(
                        "@" + Imports.annotation(source, test, true),
                        "fun %s() {".formatted(name),
                        "    "
                            + Imports.assertion(source, assertion, style.assertions(), true)
                            + " // "
                            + outcomes(suggestion),
                        "}"),
                    outcomes(suggestion)));
  }

  /// The tests suggested for the mutants of `finding`, each call once: two mutants on one side of a
  /// condition get the same call, and pasting it twice wouldn't compile.
  static List<SuggestedTest> distinct(Finding finding, SourceView sources) {
    return eachMutant(finding, sources).stream().flatMap(each -> each.test().stream()).toList();
  }

  /// One mutant of a finding, and the test suggested for it: none when there's no suggestion, or
  /// when an earlier mutant's test makes the same call.
  record ForMutant(MutantResult result, Optional<SuggestedTest> test) {}

  /// Each mutant of `finding` in order, with its test, each call once.
  static List<ForMutant> eachMutant(Finding finding, SourceView sources) {
    var names = new java.util.HashSet<String>();
    boolean shared = finding.mutants().size() > 1;
    var each = new ArrayList<ForMutant>();
    for (MutantResult result : finding.mutants()) {
      var test =
          of(result, sources)
              .map(found -> shared ? found.naming(result.mutant().id()) : found)
              .filter(found -> names.add(found.testClass() + "#" + callOf(result)));
      each.add(new ForMutant(result, test));
    }
    return each;
  }

  /// What a suggested test calls, and with what: two mutants given the same call get one test,
  /// named for the first, though another's name would say where the input sits another way.
  private static String callOf(MutantResult result) {
    return result
        .suggestion()
        .map(found -> found.call() + found.receiver() + found.inputs())
        .orElse(result.mutant().id());
  }

  /// Whether a card has a test for more than one of its mutants, so one fix can't describe them:
  /// each test then comes after its own mutant's fix.
  static boolean eachHasItsOwn(List<ForMutant> each) {
    return each.stream().filter(one -> one.test().isPresent()).count() > 1;
  }

  /// The help a card gives before its tests, when each has its own: one test each, if every mutant
  /// has one.
  static String eachHelp(List<ForMutant> each) {
    return each.stream().allMatch(one -> one.test().isPresent())
        ? "one test for each mutant"
        : "for each mutant";
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

  /// How the test class writes its assertions: JUnit 5's, JUnit 4's, AssertJ's, or TestNG's, whose
  /// `assertEquals` takes the actual value first.
  enum Style {
    JUNIT("org.junit.jupiter.api.Assertions"),
    JUNIT4("org.junit.Assert"),
    ASSERTJ("org.assertj.core.api.Assertions"),
    TESTNG("org.testng.Assert");

    private final String assertions;

    Style(String assertions) {
      this.assertions = assertions;
    }

    /// The class its assertions are static methods of.
    String assertions() {
      return assertions;
    }

    /// The style of a test class's source, by what it imports.
    static Style of(String source) {
      if (source.contains("import static org.assertj.")) {
        return ASSERTJ;
      }
      if (source.contains("import static org.testng.") || source.contains("import org.testng.")) {
        return TESTNG;
      }
      return Framework.of(source) == Framework.JUNIT4 ? JUNIT4 : JUNIT;
    }

    /// The style of a test class in Kotlin, whose imports have no `static`.
    static Style ofKotlin(String source) {
      if (source.contains("import org.assertj.")) {
        return ASSERTJ;
      }
      if (source.contains("import org.testng.")) {
        return TESTNG;
      }
      return Framework.ofKotlin(source) == Framework.JUNIT4 ? JUNIT4 : JUNIT;
    }
  }

  /// What runs the test class: JUnit 5, JUnit 4 or TestNG. JUnit 4 and TestNG run only public test
  /// methods; JUnit 4 fails the whole class over one that isn't.
  enum Framework {
    JUPITER("org.junit.jupiter.api.Test", false),
    JUNIT4("org.junit.Test", true),
    TESTNG("org.testng.annotations.Test", true);

    /// What JUnit 4 imports: `org.junit.Test`, its assertions, and its runners. Not `org.junit.`
    /// alone, which JUnit 5's `org.junit.jupiter` starts with too.
    private static final java.util.regex.Pattern JUNIT4_IMPORT =
        java.util.regex.Pattern.compile(
            "import\\s+(?:static\\s+)?org\\.junit\\.(?:(?:Test|Assert|Assume|Rule|ClassRule|Before"
                + "|After|BeforeClass|AfterClass|Ignore|runner|runners)\\b|\\*)");

    private final String test;
    private final boolean isPublic;

    Framework(String test, boolean isPublic) {
      this.test = test;
      this.isPublic = isPublic;
    }

    /// Its test annotation, in full.
    String test() {
      return test;
    }

    boolean isPublic() {
      return isPublic;
    }

    /// What runs a test class in Java, by what it imports: JUnit 5 unless it imports TestNG or
    /// JUnit 4.
    static Framework of(String source) {
      if (source.contains("import static org.testng.") || source.contains("import org.testng.")) {
        return TESTNG;
      }
      return !source.contains("org.junit.jupiter.api.Test") && JUNIT4_IMPORT.matcher(source).find()
          ? JUNIT4
          : JUPITER;
    }

    /// The same for a test class in Kotlin.
    static Framework ofKotlin(String source) {
      return of(source);
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
      case JUNIT, JUNIT4 -> "assertEquals(%s, %s);".formatted(today, call);
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
    return name(method, suggestion, List.of());
  }

  /// The name as above, but for a call of one argument, said by where its value sits rather than
  /// the value, which goes stale when the spec moves (no guide or big suite names a test by its
  /// input): at the edge of the literal the mutant's comparison has, `operands`, just below or just
  /// above it, `loyaltyCreditAtTheTotalEdge`; or zero or negative, `feeWhenAmountIsZero`. The
  /// number stays in the test's call, where a spec change is made anyway.
  static String name(String method, Suggestion suggestion, List<String> operands) {
    if (suggestion.named().isPresent()) {
      return method + "At" + pascal(suggestion.named().get().name());
    }
    return where(method, suggestion, operands).orElseGet(() -> withValues(method, suggestion));
  }

  /// `feeWhenAmountIsZero` or `…IsNegative`, or `loyaltyCreditAtTheTotalEdge`,
  /// `…JustBelowTheTotalEdge` or `…JustAboveTheTotalEdge`, for a call of one argument; none
  /// otherwise.
  private static Optional<String> where(
      String method, Suggestion suggestion, List<String> operands) {
    if (suggestion.inputs().size() != 1 || suggestion.receiverTakesArguments()) {
      return Optional.empty();
    }
    var parameter = suggestion.parameters().getFirst();
    var title = Character.toUpperCase(parameter.charAt(0)) + parameter.substring(1);
    long value = suggestion.inputs().getFirst();
    // Zero or below first: it's the call's alone, so every mutant that suggests it names it the
    // same, and pasting each card's test doesn't add one call twice.
    if (value <= 0) {
      return Optional.of(method + "When" + title + (value == 0 ? "IsZero" : "IsNegative"));
    }
    var edge = edgeOf(parameter, operands);
    if (edge.isPresent() && Math.abs(value - edge.get()) <= 1) {
      var side = value == edge.get() ? "At" : value < edge.get() ? "JustBelow" : "JustAbove";
      return Optional.of(method + side + "The" + title + "Edge");
    }
    return Optional.empty();
  }

  /// What a condition's mutant compares, `total` and `100`; nothing for another mutant, whose
  /// operands (`amount * 10`) have no edge.
  private static List<String> compared(MutantResult result) {
    var operator = result.mutant().operator();
    boolean condition =
        operator == io.mutastic.api.BuiltIn.BOUNDARY
            || operator == io.mutastic.api.BuiltIn.COMPARISON
            || operator == io.mutastic.api.BuiltIn.OTHER_COMPARISON
            || operator == io.mutastic.api.BuiltIn.REMOVE_CONDITIONAL;
    return condition ? result.mutant().operands() : List.of();
  }

  /// The literal `parameter` is compared with, when the operands are it and a whole number.
  private static Optional<Long> edgeOf(String parameter, List<String> operands) {
    if (operands.size() != 2 || !operands.contains(parameter)) {
      return Optional.empty();
    }
    var other = operands.get(operands.indexOf(parameter) == 0 ? 1 : 0);
    try {
      return Optional.of(Long.parseLong(other));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  /// `discountWithTotal100`: the method, then each argument by its name and value.
  private static String withValues(String method, Suggestion suggestion) {
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
