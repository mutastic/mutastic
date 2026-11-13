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
  }
}
