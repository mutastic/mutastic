package io.mutastic.engine.tests;

import java.nio.file.Path;
import java.util.List;

/// TestNG, run on the JUnit Platform with TestNG's engine, which mutastic brings when the build has
/// none: Gradle's `useTestNG()` and Surefire's TestNG provider run TestNG without the platform.
final class TestNg implements TestFramework {
  /// The engine, inside mutastic's jar: on no classpath, where a platform would find it.
  private static final Bundled ENGINE = new Bundled("testng-engine.jar", "mutastic-testng");

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
}
