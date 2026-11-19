package io.mutastic.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Paths are shown from where the person is, here the tests' directory.
class BuildErrorsTest {
  private static final String HERE = Path.of("").toAbsolutePath().normalize().toString();

  /// javac's error, said again under Gradle's "What went wrong", is quoted once, from the person's
  /// directory; Gradle's advice about itself is left out.
  @Test
  void aCompilerErrorIsQuotedOnceWithoutGradlesAdvice() {
    var log =
        """
        HERE/src/main/java/a/Pricing.java:18: error: ';' expected
              return total / 10
                               ^
        1 error

        FAILURE: Build failed with an exception.

        * What went wrong:
        Execution failed for task ':compileJava'.
        > Compilation failed; see the compiler output below.
          HERE/src/main/java/a/Pricing.java:18: error: ';' expected
                return total / 10
                                 ^
          1 error

        * Try:
        > Run with --scan to generate a Build Scan (powered by Develocity).
        """
            .replace("HERE", HERE)
            .lines()
            .toList();
    var build =
        new BuildErrors.Build(
            "Gradle", "pricing", Path.of(HERE, "build/mutastic/gradle.log"), "./gradlew x");

    assertThat(BuildErrors.failed(build, log))
        .isEqualTo(
            """
            pricing doesn't build, before any mutant is in it.
            The error is in src/main/java/a/Pricing.java, line 18: ';' expected.
            Gradle's output is in build/mutastic/gradle.log. To see it in full:
              ./gradlew x""");
  }

  /// javac says what it can't find a line or two after the error: a test pasted into a class that
  /// doesn't import `@Test` names it.
  @Test
  void aMissingSymbolIsNamed() {
    var javac =
        """
        HERE/src/test/java/a/CalculatorTest.java:57: error: cannot find symbol
          @Test
           ^
          symbol:   class Test
          location: class CalculatorTest
        HERE/src/test/java/a/CalculatorTest.java:59: error: cannot find symbol
            assertFalse(new Calculator().isEven(1));
            ^
          symbol:   method assertFalse(boolean)
          location: class CalculatorTest
        HERE/src/test/java/a/CalculatorTest.java:61: error: ';' expected
          symbol:   not this one's
        """
            .replace("HERE", HERE)
            .lines()
            .toList();
    var maven =
        List.of(
            "[ERROR] " + HERE + "/src/test/java/a/CalculatorTest.java:[57,4] cannot find symbol",
            "[ERROR]   symbol:   class Test",
            "[ERROR]   location: class a.CalculatorTest");

    assertThat(BuildErrors.compilerErrors(javac, Path.of(HERE)))
        .containsExactly(
            "src/test/java/a/CalculatorTest.java, line 57: cannot find symbol: class Test",
            "src/test/java/a/CalculatorTest.java, line 59: cannot find symbol: method"
                + " assertFalse(boolean)",
            "src/test/java/a/CalculatorTest.java, line 61: ';' expected");
    assertThat(BuildErrors.compilerErrors(maven, Path.of(HERE)))
        .containsExactly(
            "src/test/java/a/CalculatorTest.java, line 57: cannot find symbol: class Test");
  }

  @Test
  void kotlinAndMavensErrorsAreQuotedAtTheirLine() {
    var kotlin =
        List.of("e: file://" + HERE + "/src/main/kotlin/A.kt:3:5 Unresolved reference 'x'.");
    var maven =
        List.of(
            "[ERROR] COMPILATION ERROR : ",
            "[ERROR] " + HERE + "/core/src/main/java/a/Fees.java:[7,20] ';' expected",
            "[ERROR] " + HERE + "/core/src/main/java/a/Fees.java:[9,4] ';' expected",
            "[ERROR] " + HERE + "/core/src/main/java/a/Fees.java:[11,4] ';' expected",
            "[ERROR] " + HERE + "/core/src/main/java/a/Fees.java:[12,4] ';' expected");
    var build = new BuildErrors.Build("Maven", "m/core", Path.of(HERE, "m.log"), "mvn x");

    assertThat(BuildErrors.compilerErrors(kotlin, Path.of(HERE)))
        .containsExactly("src/main/kotlin/A.kt, line 3: Unresolved reference 'x'.");
    assertThat(BuildErrors.failed(build, maven))
        .startsWith(
            """
            m/core doesn't build, before any mutant is in it. Its first 3 errors, of 4:
              core/src/main/java/a/Fees.java, line 7: ';' expected
            """)
        .contains("  core/src/main/java/a/Fees.java, line 11: ';' expected\n")
        .doesNotContain("line 12");
  }

  /// With no compiler error, what the tool says went wrong: Gradle's section, Maven's errors
  /// without its advice, or the end of what a wrapper said before any build began.
  @Test
  void otherwiseWhatWentWrongIsQuoted() {
    var gradle =
        List.of(
            "FAILURE: Build failed with an exception.",
            "* What went wrong:",
            "Could not resolve all files for configuration ':compileClasspath'.",
            "* Try:",
            "> Run with --stacktrace option to get the stack trace.");
    var maven =
        List.of(
            "[ERROR] Failed to execute goal on project core: Could not resolve dependencies",
            "[ERROR] ",
            "[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.",
            "[ERROR] -> [Help 1]");

    assertThat(BuildErrors.whatWentWrong(gradle))
        .hasValue("  Could not resolve all files for configuration ':compileClasspath'.");
    assertThat(BuildErrors.whatWentWrong(maven))
        .hasValue("  Failed to execute goal on project core: Could not resolve dependencies");
    assertThat(BuildErrors.whatWentWrong(List.of("Exception: x.lck (Operation not permitted)")))
        .hasValue("  Exception: x.lck (Operation not permitted)");
  }

  @Test
  void theCommandIsTypedFromWhereThePersonIs() {
    assertThat(BuildErrors.command(HERE + "/gradlew", Path.of(HERE), "-p", List.of("testClasses")))
        .isEqualTo("./gradlew testClasses");
    assertThat(
            BuildErrors.command(
                HERE + "/a/gradlew", Path.of(HERE, "a"), "-p", List.of("testClasses")))
        .isEqualTo("a/gradlew -p a testClasses");
    assertThat(BuildErrors.command("mvn", Path.of(HERE, "m"), "-f", List.of("test")))
        .isEqualTo("mvn -f m test");
    assertThat(
            BuildErrors.command(
                List.of("sh", HERE + "/gradlew"), Path.of(HERE), "-p", List.of("testClasses")))
        .isEqualTo("sh ./gradlew testClasses");
  }

  /// What likely made the build fail comes after what went wrong, before where its output is.
  @Test
  void whatLikelyMadeItFailIsSaidBeforeTheLog() {
    var build = new BuildErrors.Build("Gradle", "shop", Path.of("w/gradle.log"), "gradle build");

    assertThat(
            BuildErrors.failed(
                build,
                List.of("* What went wrong:", "Could not find method x()", "", "* Try:"),
                java.util.Optional.of("It ran with Gradle 8.2.1 from the PATH.")))
        .contains("Could not find method x()")
        .contains("x()\nIt ran with Gradle 8.2.1 from the PATH.\nGradle's output is in ")
        .endsWith("To see it in full:\n  gradle build");
    assertThat(
            BuildErrors.failed(
                build,
                List.of("/abs/Pricing.java:18: error: ';' expected"),
                java.util.Optional.of("It ran with Gradle 8.2.1 from the PATH.")))
        .doesNotContain("It ran with");
  }
}
