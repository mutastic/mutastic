package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/// A suggested test names its annotation and assertion as the class can read them: by the simple
/// name it imports, else in full, so it compiles as pasted.
class ImportsTest {
  private static final String JUPITER = "org.junit.jupiter.api.Test";
  private static final String ASSERTIONS = "org.junit.jupiter.api.Assertions";

  @Test
  void whatTheClassImportsIsWrittenShort() {
    var source =
        """
        import static org.junit.jupiter.api.Assertions.assertEquals;
        import org.junit.jupiter.api.*;
        """;

    assertThat(Imports.annotation(source, JUPITER, false)).isEqualTo("Test");
    assertThat(Imports.assertion(source, "assertEquals(1, f());", ASSERTIONS, false))
        .isEqualTo("assertEquals(1, f());");
  }

  /// A class of only parameterized tests imports neither `@Test` nor `assertFalse`.
  @Test
  void whatItDoesntImportIsWrittenInFull() {
    var source =
        """
        import static org.junit.jupiter.api.Assertions.assertEquals;
        import org.junit.jupiter.params.ParameterizedTest;
        """;

    assertThat(Imports.annotation(source, JUPITER, false)).isEqualTo(JUPITER);
    assertThat(Imports.assertion(source, "assertFalse(f());", ASSERTIONS, false))
        .isEqualTo("org.junit.jupiter.api.Assertions.assertFalse(f());");
  }

  @Test
  void anImportedClassQualifiesItsAssertion() {
    var source = "import org.junit.jupiter.api.Assertions;\nimport org.junit.jupiter.api.Test;\n";

    assertThat(Imports.assertion(source, "assertTrue(f());", ASSERTIONS, false))
        .isEqualTo("Assertions.assertTrue(f());");
  }

  /// Kotlin imports have no `static` and no semicolon, and `kotlin.test`'s are as good.
  @Test
  void kotlinImportsAreRead() {
    var source = "import kotlin.test.Test\nimport kotlin.test.assertEquals\n";

    assertThat(Imports.annotation(source, JUPITER, true)).isEqualTo("Test");
    assertThat(Imports.assertion(source, "assertEquals(1, f())", ASSERTIONS, true))
        .isEqualTo("assertEquals(1, f())");
    assertThat(Imports.assertion(source, "assertFalse(f())", ASSERTIONS, true))
        .isEqualTo("org.junit.jupiter.api.Assertions.assertFalse(f())");
  }

  /// JUnit 4's test methods must be public; JUnit 5's needn't.
  @Test
  void aJUnit4ClassIsKnownByWhatItImports() {
    assertThat(SuggestedTest.Framework.of("import org.junit.Test;\n"))
        .isEqualTo(SuggestedTest.Framework.JUNIT4);
    assertThat(SuggestedTest.Framework.of("import static org.junit.Assert.assertEquals;\n"))
        .isEqualTo(SuggestedTest.Framework.JUNIT4);
    assertThat(SuggestedTest.Framework.of("import org.junit.jupiter.api.Test;\n"))
        .isEqualTo(SuggestedTest.Framework.JUPITER);
    assertThat(SuggestedTest.Framework.JUNIT4.isPublic()).isTrue();
    assertThat(SuggestedTest.Framework.JUPITER.isPublic()).isFalse();
  }
}
