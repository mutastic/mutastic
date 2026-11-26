package io.mutastic.engine.tests;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/// What a project's test classpath needs from mutastic for its tests to run on the JUnit Platform.
class TestFrameworksTest {
  private static TestFrameworks frameworks(String... names) {
    return TestFrameworks.of(
        java.util.Arrays.stream(names).map(name -> Path.of("lib", name)).toList());
  }

  private static List<String> support(String... names) {
    return frameworks(names).support().stream().map(jar -> jar.getFileName().toString()).toList();
  }

  /// Gradle's `useJUnit()`, as `kotlin("test")` picks: JUnit 4 and no platform.
  @Test
  void junit4AloneRunsOnTheVintageEngine() {
    assertThat(support("kotlin-test-junit-2.2.21.jar", "junit-4.13.2.jar", "hamcrest-core-1.3.jar"))
        .anyMatch(jar -> jar.startsWith("junit-platform-engine-"))
        .anyMatch(jar -> jar.startsWith("mutastic-junit4"))
        .noneMatch(jar -> jar.startsWith("mutastic-testng"));
  }

  /// The build runs JUnit 4 on the platform already, with its own engine.
  @Test
  void junit4OnThePlatformNeedsNothing() {
    assertThat(
            support(
                "junit-4.13.2.jar",
                "junit-vintage-engine-5.12.2.jar",
                "junit-platform-engine-1.12.2.jar"))
        .isEmpty();
  }

  /// No tests and no JUnit: the platform alone, for the worker to start.
  @Test
  void noJunitAtAllGetsThePlatform() {
    assertThat(support("commons-lang3-3.17.0.jar"))
        .isNotEmpty()
        .allMatch(jar -> !jar.startsWith("mutastic-"));
  }

  @Test
  void testngWithoutItsEngineGetsIt() {
    assertThat(support("testng-7.10.2.jar")).anyMatch(jar -> jar.startsWith("mutastic-testng"));
    assertThat(support("testng-7.10.2.jar", "testng-engine-1.0.5.jar"))
        .noneMatch(jar -> jar.startsWith("mutastic-testng"));
  }

  /// TestNG and JUnit 4 with no platform: TestNG's engine alone, as it always had.
  @Test
  void testngComesBeforeJunit4() {
    assertThat(support("testng-7.10.2.jar", "junit-4.13.2.jar"))
        .anyMatch(jar -> jar.startsWith("mutastic-testng"))
        .noneMatch(jar -> jar.startsWith("mutastic-junit4"));
  }

  @Test
  void quarkusLoadsTheApp() {
    assertThat(frameworks("quarkus-junit5-3.17.0.jar").loadsTheApp()).isTrue();
    assertThat(frameworks("junit-jupiter-5.12.2.jar").loadsTheApp()).isFalse();
  }

  /// The build's platform with no launcher, when the build tool couldn't add the build's own: the
  /// worker can't start, and mutastic's launcher would be another version, so it says what to add.
  @Test
  void aPlatformWithNoLauncherIsRefused() {
    assertThat(
            frameworks("junit-jupiter-engine-5.10.2.jar", "junit-platform-engine-1.10.2.jar")
                .refusal())
        .hasValueSatisfying(
            said ->
                assertThat(said)
                    .contains("JUnit Platform 1.10.2")
                    .contains("testRuntimeOnly(\"org.junit.platform:junit-platform-launcher\")"));
    assertThat(support("junit-platform-engine-1.10.2.jar")).isEmpty();
  }

  @Test
  void aPlatformWithItsLauncherRuns() {
    assertThat(
            frameworks("junit-platform-engine-1.10.2.jar", "junit-platform-launcher-1.10.2.jar")
                .refusal())
        .isEmpty();
    assertThat(frameworks("commons-lang3-3.17.0.jar").refusal()).isEmpty();
  }
}
