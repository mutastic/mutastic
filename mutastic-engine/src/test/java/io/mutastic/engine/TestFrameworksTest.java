package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/// What a project's test classpath needs from mutastic for its tests to run on the JUnit Platform.
class TestFrameworksTest {
  private static List<Path> jars(String... names) {
    return java.util.Arrays.stream(names).map(name -> Path.of("lib", name)).toList();
  }

  /// Gradle's `useJUnit()`, as `kotlin("test")` picks: JUnit 4 and no platform.
  @Test
  void junit4AloneRunsOnTheVintageEngine() {
    var classpath =
        jars("kotlin-test-junit-2.2.21.jar", "junit-4.13.2.jar", "hamcrest-core-1.3.jar");

    assertThat(TestFrameworks.engineNeeded(classpath)).contains(TestFrameworks.Engine.JUNIT4);
    assertThat(TestFrameworks.support(classpath))
        .anyMatch(jar -> jar.getFileName().toString().startsWith("junit-platform-engine-"))
        .anyMatch(jar -> jar.getFileName().toString().startsWith("mutastic-junit4"));
  }

  /// The build runs JUnit 4 on the platform already, with its own engine.
  @Test
  void junit4OnThePlatformNeedsNothing() {
    var classpath =
        jars(
            "junit-4.13.2.jar",
            "junit-vintage-engine-5.12.2.jar",
            "junit-platform-engine-1.12.2.jar");

    assertThat(TestFrameworks.engineNeeded(classpath)).isEmpty();
    assertThat(TestFrameworks.support(classpath)).isEmpty();
  }

  /// No tests and no JUnit: the platform alone, for the worker to start.
  @Test
  void noJunitAtAllGetsThePlatform() {
    var classpath = jars("commons-lang3-3.17.0.jar");

    assertThat(TestFrameworks.engineNeeded(classpath)).isEmpty();
    assertThat(TestFrameworks.support(classpath))
        .isNotEmpty()
        .allMatch(jar -> !jar.getFileName().toString().startsWith("mutastic-"));
  }

  @Test
  void testngWithoutItsEngineGetsIt() {
    assertThat(TestFrameworks.engineNeeded(jars("testng-7.10.2.jar")))
        .contains(TestFrameworks.Engine.TESTNG);
    assertThat(TestFrameworks.engineNeeded(jars("testng-7.10.2.jar", "testng-engine-1.0.5.jar")))
        .isEmpty();
  }
}
