package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Status;
import io.mutastic.api.Suggestion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A suggested test, and a card's words, for what kotlinc compiled. `KotlinShapesTest` compiles
/// them and runs them against the mutants: here is each form.
class KotlinSuggestedTest {
  private static final SuggestedTest.Style JUNIT = SuggestedTest.Style.JUNIT;
  private static final SuggestedTest.Style ASSERTJ = SuggestedTest.Style.ASSERTJ;
  private static final SuggestedTest.Style TESTNG = SuggestedTest.Style.TESTNG;

  @TempDir Path project;

  @BeforeEach
  void source() throws Exception {
    var file = project.resolve("src/main/kotlin/probe/Probe.kt");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        """
        package probe
        fun plusOne(n: Int): Int = n + 1
        fun blank(name: String) = name.isEmpty()
        fun filled(name: String) = name.isNotEmpty()
        fun longEnough(name: String) = name.length > 2
        """);
  }

  private static Suggestion suggestion(String today, String mutated) {
    return new Suggestion("plusOne(1)", List.of("n"), List.of(1L), today, mutated);
  }

  private static String assertion(String today, SuggestedTest.Style style) {
    return KotlinAssertion.of(suggestion(today, "0"), style).orElseThrow();
  }

  @Test
  void anAssertionInEachStyle() {
    assertThat(assertion("2", JUNIT)).isEqualTo("assertEquals(2, plusOne(1))");
    assertThat(assertion("2", TESTNG)).isEqualTo("assertEquals(plusOne(1), 2)");
    assertThat(assertion("2", ASSERTJ)).isEqualTo("assertThat(plusOne(1)).isEqualTo(2)");
    assertThat(assertion("true", JUNIT)).isEqualTo("assertTrue(plusOne(1))");
    assertThat(assertion("false", JUNIT)).isEqualTo("assertFalse(plusOne(1))");
    assertThat(assertion("true", ASSERTJ)).isEqualTo("assertThat(plusOne(1)).isTrue()");
    assertThat(assertion("false", ASSERTJ)).isEqualTo("assertThat(plusOne(1)).isFalse()");
    assertThat(assertion("null", JUNIT)).isEqualTo("assertNull(plusOne(1))");
    assertThat(assertion("null", ASSERTJ)).isEqualTo("assertThat(plusOne(1)).isNull()");
  }

  @Test
  void whatThrowsGoesInALambda() {
    var throwing = "throws java.lang.IllegalStateException";

    assertThat(assertion(throwing, JUNIT))
        .isEqualTo("assertThrows(java.lang.IllegalStateException::class.java) { plusOne(1) }");
    assertThat(assertion(throwing, ASSERTJ))
        .isEqualTo(
            "assertThatThrownBy { plusOne(1) }.isInstanceOf(java.lang.IllegalStateException::class.java)");
  }

  @Test
  void aDollarInAStringIsKeptFromBeingATemplate() {
    assertThat(assertion("\"$up\"", JUNIT)).isEqualTo("assertEquals(\"\\$up\", plusOne(1))");
    assertThat(assertion("'$'", JUNIT)).isEqualTo("assertEquals('$', plusOne(1))");
  }

  @Test
  void aValueKotlinCantReadBackHasNoAssertion() {
    for (var value : List.of("NaN", "Infinity", "-Infinity", "NaNf", "Infinityf", "-Infinityf")) {
      assertThat(KotlinAssertion.of(suggestion(value, "0"), JUNIT)).as(value).isEmpty();
    }
  }

  @Test
  void aTestClassInKotlinSaysItsStyleByItsImports() {
    assertThat(SuggestedTest.Style.ofKotlin("import org.assertj.core.api.Assertions.assertThat"))
        .isEqualTo(ASSERTJ);
    assertThat(SuggestedTest.Style.ofKotlin("import org.testng.Assert.assertEquals"))
        .isEqualTo(TESTNG);
    assertThat(SuggestedTest.Style.ofKotlin("import org.junit.jupiter.api.Test")).isEqualTo(JUNIT);
  }

  private SourceView sources() {
    return new SourceView(
        project, List.of(project.resolve("src/main/kotlin")), List.of(project.resolve("src/test")));
  }

  private static Mutant mutant(int line, List<String> operands) {
    return new Mutant(
        "probe/ProbeKt",
        "plusOne",
        "(I)I",
        0,
        line,
        BuiltIn.REMOVE_CONDITIONAL,
        "IFNE",
        "always jump",
        Optional.empty(),
        List.of(),
        operands);
  }

  private static MutantResult missed(Suggestion suggestion) {
    return new MutantResult(
            mutant(2, List.of()),
            Status.MISSED,
            List.of("probe.ProbeTest#calls"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            1,
            Optional.empty(),
            Map.of(),
            Optional.empty(),
            Duration.ZERO)
        .withSuggestion(Optional.of(suggestion));
  }

  private void testFile(String name, String text) throws Exception {
    var file = project.resolve("src/test/probe").resolve(name);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  @Test
  void aTestIsInKotlinWhereTheMutatedFileIs() throws Exception {
    testFile(
        "ProbeTest.kt", "import org.assertj.core.api.Assertions.assertThat\nclass ProbeTest\n");

    var test = SuggestedTest.of(missed(suggestion("2", "0")), sources()).orElseThrow();

    assertThat(test.testClass()).isEqualTo("ProbeTest");
    assertThat(test.lines())
        .containsExactly(
            "@Test",
            "fun plusOneWithN1() {",
            "    assertThat(plusOne(1)).isEqualTo(2) // today 2 · with this mutant 0",
            "}");
    assertThat(sources().testFile("probe.ProbeTest")).contains("src/test/probe/ProbeTest.kt");
  }

  /// A call in Kotlin can't be pasted into a test class in Java.
  @Test
  void aTestClassInJavaGetsNoKotlin() throws Exception {
    testFile("ProbeTest.java", "class ProbeTest {}\n");

    assertThat(SuggestedTest.of(missed(suggestion("2", "0")), sources())).isEmpty();
  }

  @Test
  void aValueThatCantBeWrittenHasNoTest() {
    assertThat(SuggestedTest.of(missed(suggestion("NaN", "0")), sources())).isEmpty();
  }

  @Test
  void aStartingPointIsInKotlinToo() {
    assertThat(SuggestedTest.startingPoints(List.of(missed(suggestion("2", "0"))), sources(), 1))
        .containsExactly("assertEquals(2, plusOne(1))");
  }

  @Test
  void aConditionIsAsTheLineWritesIt() {
    assertThat(sources().advice(mutant(3, List.of("length()", "0"))).change())
        .isEqualTo("`name.isEmpty()` is always false");
    assertThat(sources().advice(mutant(5, List.of("length()", "2"))).fix())
        .doesNotContain("length()");
  }

  @Test
  void aNotNullCheckIsTheSources() {
    var bang =
        new Mutant(
            "probe/ProbeKt",
            "keep",
            "(Ljava/lang/String;)Ljava/lang/String;",
            0,
            2,
            BuiltIn.REMOVE_VOID_CALL,
            "INVOKESTATIC kotlin/jvm/internal/Intrinsics.checkNotNull(Ljava/lang/Object;)V",
            "remove call",
            Optional.empty());
    var cast =
        new Mutant(
            "probe/ProbeKt",
            "cast",
            "(Ljava/lang/Object;)Ljava/lang/String;",
            0,
            2,
            BuiltIn.REMOVE_VOID_CALL,
            "INVOKESTATIC kotlin/jvm/internal/Intrinsics.checkNotNull(Ljava/lang/Object;Ljava/lang/String;)V",
            "remove call",
            Optional.empty());

    assertThat(sources().advice(bang).headline())
        .isEqualTo("no test has a `null` reach the `!!` in `keep()`");
    assertThat(sources().advice(bang).change()).isEqualTo("the `!!` lets a `null` through");
    assertThat(sources().advice(cast).headline())
        .isEqualTo("no test has a `null` reach the `as` in `cast()`");
    assertThat(sources().advice(bang).fix()).contains("throws a `NullPointerException`");
  }

  @Test
  void kotlincsNamesAsTheSourceHasThem() {
    assertThat(where("probe/ProbeKt", "labels$lambda$0"))
        .isEqualTo(new SourceNames.Where("ProbeKt", "labels", Optional.of("a lambda")));
    assertThat(where("probe/Lazy", "v_delegate$lambda$0"))
        .isEqualTo(new SourceNames.Where("Lazy", "v", Optional.of("a lambda")));
    assertThat(where("probe/ProbeKt$seq$1", "invokeSuspend"))
        .isEqualTo(new SourceNames.Where("ProbeKt", "seq", Optional.of("a lambda")));
    assertThat(where("probe/Outer$Inner$seq$12", "invokeSuspend"))
        .isEqualTo(new SourceNames.Where("Outer.Inner", "seq", Optional.of("a lambda")));
    assertThat(where("probe/ProbeKt$seq$1", "other"))
        .isEqualTo(
            new SourceNames.Where("ProbeKt.seq", "other", Optional.of("an anonymous class")));
  }

  private static SourceNames.Where where(String className, String method) {
    return SourceNames.of(
        new Mutant(
            className,
            method,
            "()I",
            0,
            1,
            BuiltIn.RETURN_VALUE,
            "IRETURN",
            "return 0",
            Optional.empty()));
  }
}
