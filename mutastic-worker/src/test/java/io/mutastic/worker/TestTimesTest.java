package io.mutastic.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import io.mutastic.worker.fixture.SlowSetup;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

class TestTimesTest {
  private static final String CLASS = SlowSetup.class.getName();

  /// A mutant run loads a fresh copy of the test class, so each test pays the class's setup again:
  /// a Spring context, here a 400 ms `@BeforeAll`. A test's timeout must allow for it.
  @Test
  void eachTestCountsItsClasssSetupAndAllItsCases() {
    var started = new ArrayList<String>();
    var times = new LinkedHashMap<String, Duration>();
    var listener =
        new TestTimes(
            Worker::id, started::add, (test, nanos) -> times.put(test, Duration.ofNanos(nanos)));

    LauncherFactory.create()
        .execute(
            LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(SlowSetup.class))
                .build(),
            listener);

    var quick = CLASS + "#quick";
    var eachCase = CLASS + "#eachCase(int)";
    assertThat(times).containsOnlyKeys(quick, eachCase);
    assertThat(times.get(quick)).isGreaterThanOrEqualTo(Duration.ofMillis(400));
    assertThat(times.get(eachCase)).isGreaterThanOrEqualTo(Duration.ofMillis(400 + 3 * 50));
    assertThat(started).containsExactlyInAnyOrder(quick, eachCase, eachCase, eachCase);
  }
}
