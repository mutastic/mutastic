package io.mutastic.engine.tests;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

  /// No engine at all, with JUnit 3 on the classpath and no JUnit 4: the vintage engine runs JUnit
  /// 3's tests only through JUnit 4's runner, so there's nothing to run them with.
  @Override
  public Optional<String> unstarted(TestClasspath classpath, StartFailure failure) {
    boolean junit3Alone =
        classpath.hasMatching("junit-3\\..*\\.jar") && !classpath.hasMatching("junit-4\\..*\\.jar");
    if (!junit3Alone || !failure.says("without at least one TestEngine")) {
      return Optional.empty();
    }
    return Optional.of(
        "The tests are JUnit 3's alone, which mutastic runs on the JUnit Platform with JUnit 4's"
            + " runner, through the vintage engine. Change junit:junit to 4.13.2, which runs"
            + " JUnit 3's tests as they are.");
  }
}
