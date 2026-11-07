package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/// What the guard runs again for each catcher. Whether it finds a false catch, and what the run
/// says of it, is `FalseCatchesTest`'s, on a sample with some.
class FalseCatchGuardTest {
  @Test
  void aMethodOrAClassRunsAsItIs() {
    assertThat(FalseCatchGuard.runnable("com.example.ATest#a(java.nio.file.Path)"))
        .hasValue("com.example.ATest#a(java.nio.file.Path)");
    assertThat(FalseCatchGuard.runnable("com.example.ATest")).hasValue("com.example.ATest");
  }

  /// A class that failed as a whole, in a `@BeforeAll`, is reported by its unique id, which the
  /// worker can't select: it runs by its name, nested classes and all.
  @Test
  void aClassThatFailedAsAWholeRunsByItsName() {
    assertThat(FalseCatchGuard.runnable("[engine:junit-jupiter]/[class:com.example.ATest]"))
        .hasValue("com.example.ATest");
    assertThat(
            FalseCatchGuard.runnable(
                "[engine:junit-jupiter]/[class:com.example.ATest]/[nested-class:Inner]"
                    + "/[nested-class:Deeper]"))
        .hasValue("com.example.ATest$Inner$Deeper");
  }

  @Test
  void anIdWithNoClassCantRunAgain() {
    assertThat(FalseCatchGuard.runnable("[engine:junit-vintage]/[runner:Suite]")).isEmpty();
  }
}
