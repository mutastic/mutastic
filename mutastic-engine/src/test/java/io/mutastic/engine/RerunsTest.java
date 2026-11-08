package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.classfile.ClassFile;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/// What the coverage pass's failures are run again as, and what a class left out as flaky leaves
/// out. `BaselineTest` runs it on `samples/flaky-setup`, a flaky class setup in each framework.
class RerunsTest {
  private static final String SETUP = "com.example.SetupTest";

  @Test
  void aClassIsNamedByItselfAndAnEngineByThePlatformsId() {
    assertThat(TestIds.isClass(SETUP)).isTrue();
    assertThat(TestIds.isClass(SETUP + "#reads")).isFalse();
    assertThat(TestIds.isClass("[engine:junit-jupiter]")).isFalse();
    assertThat(TestIds.canRunAgain(SETUP)).isTrue();
    assertThat(TestIds.canRunAgain("[engine:junit-jupiter]")).isFalse();
  }

  @Test
  void aClassThatFailedStandsForTheTestsThatFailedWithIt() {
    // TestNG says each test of a class whose setup failed failed too.
    var failing =
        List.of(SETUP + "#reads", SETUP + "$Inner#nested", SETUP, "com.example.OtherTest#a");

    assertThat(Reruns.withoutTheirClasses(failing))
        .containsExactly(SETUP, "com.example.OtherTest#a");
  }

  @Test
  void leavingAClassOutLeavesOutEveryTestInIt() {
    var coverage = new Coverage(ClassFile.of());
    for (var test :
        List.of(
            SETUP + "#reads",
            SETUP + "$Inner#nested",
            "com.example.SetupTestTwo#a",
            "com.example.OtherTest#a")) {
      coverage.recordTest(test, Duration.ofMillis(1));
    }

    coverage.exclude(List.of(SETUP, "com.example.OtherTest#a"));

    // A class whose name only starts the same is another class.
    assertThat(coverage.testCount()).isEqualTo(1);
    assertThat(coverage.timeOf(List.of("com.example.SetupTestTwo#a")))
        .isEqualTo(Duration.ofMillis(1));
  }
}
