package io.mutastic.engine.tests;

import java.nio.file.Path;
import java.util.List;

/// JUnit 4, run on the JUnit Platform with the vintage engine, which mutastic brings when the build
/// runs JUnit 4 with its own runner (Gradle's `useJUnit()`, as `kotlin("test")` picks): a classpath
/// with JUnit 4 and no platform. TestNG's tests come first: a classpath with both gets TestNG's
/// engine alone, as it always has.
final class Vintage implements TestFramework {
  private static final Bundled ENGINE = new Bundled("vintage-engine.jar", "mutastic-junit4");

  @Override
  public List<Path> support(TestClasspath classpath) {
    boolean junit4 = classpath.hasMatching("junit-4\\..*\\.jar");
    return junit4 && !classpath.hasPlatform() && !TestNg.usedBy(classpath)
        ? List.of(ENGINE.copied())
        : List.of();
  }
}
