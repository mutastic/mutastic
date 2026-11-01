package io.mutastic.worker.fixture;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// Shaped like a Spring test: slow class setup, then quick tests. Run by [TestTimesTest], not by
/// the build (the `fixture` tag is excluded).
@Tag("fixture")
public class SlowSetup {
  @BeforeAll
  static void startsSlowly() throws InterruptedException {
    Thread.sleep(400);
  }

  @Test
  void quick() {}

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  void eachCase(int ignored) throws InterruptedException {
    Thread.sleep(50);
  }
}
