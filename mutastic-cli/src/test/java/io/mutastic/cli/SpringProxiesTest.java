package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Spring 5's CGLIB proxies, in the `spring-proxies` sample, on one worker so every mutant runs in
/// one JVM. Spring keeps the proxy it made of `Prices` by the thread's context class loader, which
/// was each run's own, and defines it beside `Prices`, in the loader that outlasts the runs: from
/// the second run on, the JVM refused it as defined twice, and the test failed on that.
///
/// Such a failure judged nothing, so it isn't a catch. `transactions()` returning null changes
/// nothing `ShopTest` checks, since Spring takes a null bean, and its run failed only on the
/// duplicate: the mutant survives, and names the test. `PricesTest`, which failed the same way when
/// run again on its own, isn't taken for flaky, so its catches count.
class SpringProxiesTest {
  private static final Path SAMPLE =
      Path.of(System.getProperty("mutastic.samples"), "spring-proxies");

  @TempDir static Path work;

  private static List<String> events;

  @BeforeAll
  static void runTheSample() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("spring-proxies"));
    var run =
        Samples.mutastic(
            "run", project.toString(), "--workers=1", "--kill-matrix", "--format=jsonl");
    events = run.out().lines().toList();
  }

  @Test
  void aFailureOnlyOnAClassDefinedTwiceIsntACatch() {
    assertThat(mutant("transactions()"))
        .contains("\"status\":\"Survived\"")
        .contains("\"duplicateClassBy\":\"demo.ShopTest#theShopHasPrices\"");
  }

  @Test
  void aTestThatFailedThatWayOnItsOwnIsntTakenForFlaky() {
    assertThat(events)
        .filteredOn(line -> line.contains("\"reason\":\"run-finished\""))
        .singleElement()
        .asString()
        .contains("\"flaky\":[]")
        .contains("\"killed\":7");
  }

  /// The one mutant of `Shop.<method>`.
  private static String mutant(String method) {
    var found =
        events.stream()
            .filter(line -> line.contains("\"reason\":\"mutant-finished\""))
            .filter(line -> line.contains("\"method\":\"" + method))
            .toList();
    assertThat(found).as(method).hasSize(1);
    return found.getFirst();
  }
}
