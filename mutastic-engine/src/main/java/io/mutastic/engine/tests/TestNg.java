package io.mutastic.engine.tests;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/// TestNG, run on the JUnit Platform with TestNG's engine, which mutastic brings when the build has
/// none: Gradle's `useTestNG()` and Surefire's TestNG provider run TestNG without the platform.
final class TestNg implements TestFramework {
  /// The engine, inside mutastic's jar: on no classpath, where a platform would find it.
  private static final Bundled ENGINE = new Bundled("testng-engine.jar", "mutastic-testng");

  /// TestNG's jar, `testng-6.9.10.jar`, and its version.
  private static final Pattern JAR = Pattern.compile("testng-(?<version>\\d[^/]*)\\.jar");

  /// The oldest TestNG the engine runs: it calls `addListener(ITestNGListener)`, which 6.14.3
  /// added.
  static final String OLDEST = "6.14.3";

  /// Whether the tests use TestNG: `testng-7.10.2.jar`, not the engine's own jar.
  static boolean usedBy(TestClasspath classpath) {
    return classpath.hasMatching("testng-\\d.*\\.jar");
  }

  @Override
  public List<Path> support(TestClasspath classpath) {
    return usedBy(classpath) && !classpath.has("testng-engine-")
        ? List.of(ENGINE.copied())
        : List.of();
  }

  /// TestNG's engine calling a method its TestNG hasn't got: a TestNG older than the engine runs.
  @Override
  public Optional<String> unstarted(TestClasspath classpath, StartFailure failure) {
    if (!usedBy(classpath)
        || !failure.is("java.lang.NoSuchMethodError")
        || !failure.says("testng")) {
      return Optional.empty();
    }
    var version =
        classpath.names().stream()
            .map(JAR::matcher)
            .filter(java.util.regex.Matcher::matches)
            .map(matcher -> matcher.group("version"))
            .findFirst()
            .map(found -> " " + found)
            .orElse("");
    return Optional.of(
        ("mutastic runs TestNG's tests on the JUnit Platform with TestNG's engine, which runs"
                + " TestNG %s or later, and this build has TestNG%s. Upgrade org.testng:testng to"
                + " %s or later.")
            .formatted(OLDEST, version, OLDEST));
  }
}
