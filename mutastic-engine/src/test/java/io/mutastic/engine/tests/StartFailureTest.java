package io.mutastic.engine.tests;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/// What the tests failing to start means, by the framework whose doing it is.
class StartFailureTest {
  private static final StartFailure OLD_TESTNG =
      StartFailure.of(
          "org.junit.platform.commons.JUnitException",
          "TestEngine with ID 'testng' failed to discover tests",
          "java.lang.NoSuchMethodError",
          "'void org.junit.support.testng.engine.TestNGTestEngine$ConfigurableTestNG"
              + ".addListener(org.testng.ITestNGListener)'");

  private static final StartFailure NO_ENGINE =
      StartFailure.of(
          "org.junit.platform.commons.PreconditionViolationException",
          "Cannot create Launcher without at least one TestEngine",
          "-",
          "-");

  private static TestFrameworks frameworks(String... names) {
    return TestFrameworks.of(
        java.util.Arrays.stream(names).map(name -> Path.of("lib", name)).toList());
  }

  @Test
  void saidWithItsCause() {
    assertThat(OLD_TESTNG.said())
        .startsWith(
            "TestEngine with ID 'testng' failed to discover tests: NoSuchMethodError: 'void");
    assertThat(NO_ENGINE.said())
        .isEqualTo("Cannot create Launcher without at least one TestEngine");
  }

  @Test
  void anOldTestNgNamesItsVersion() {
    assertThat(frameworks("testng-6.9.10.jar", "jcommander-1.48.jar").unstarted(OLD_TESTNG))
        .hasValueSatisfying(said -> assertThat(said).contains("this build has TestNG 6.9.10"));
  }

  @Test
  void junit3AloneSaysToChangeIt() {
    assertThat(frameworks("junit-3.8.2.jar").unstarted(NO_ENGINE))
        .hasValueSatisfying(said -> assertThat(said).contains("junit:junit to 4.13.2"));
    assertThat(frameworks("junit-3.8.2.jar", "junit-4.13.2.jar").unstarted(NO_ENGINE)).isEmpty();
  }

  /// A failure no framework knows is said as the worker said it, with nothing added.
  @Test
  void noneKnownAddsNothing() {
    assertThat(frameworks("junit-jupiter-engine-5.12.2.jar").unstarted(OLD_TESTNG)).isEmpty();
  }
}
