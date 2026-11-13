package io.mutastic.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import io.mutastic.worker.fixture.SlowSetup;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

class TestsStartingTest {
  private static final String CLASS = SlowSetup.class.getName();

  /// Progress names the test a long coverage pass is in: each test by its id as it starts, a
  /// parameterised test's cases each again, and no class.
  @Test
  void eachTestIsSaidAsItStarts() {
    var started = new ArrayList<String>();

    LauncherFactory.create()
        .execute(
            LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(SlowSetup.class))
                .build(),
            new TestsStarting(Worker::id, started::add));

    var eachCase = CLASS + "#eachCase(int)";
    assertThat(started).containsExactlyInAnyOrder(CLASS + "#quick", eachCase, eachCase, eachCase);
  }
}
