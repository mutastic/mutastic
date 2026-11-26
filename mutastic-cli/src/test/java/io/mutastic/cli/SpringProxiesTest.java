package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Spring 5's CGLIB proxies, in the `spring-proxies` sample, on one worker so every mutant runs in
/// one JVM. Spring keeps the proxy it made of `Prices` by the thread's context class loader, and
/// defines it beside `Prices`, in the loader that outlasts the runs. With each run's own context
/// loader, from the second run on the JVM refused it as defined twice and the test failed on that,
/// which looked like a catch. With one context loader for the worker's life, as the build has one,
/// Spring finds the proxy it made, and nothing is defined twice.
///
/// `transactions()` returning null changes nothing `ShopTest` checks, since Spring takes a null
/// bean: the mutant survives, and no test failed on a class defined twice. `PricesTest` passes when
/// run again on its own, so it isn't taken for flaky and its catches count.
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
  void aMutantNoTestSeesSurvivesAndNothingIsDefinedTwice() {
    assertThat(mutant("transactions()")).contains("\"status\":\"Survived\"");
    assertThat(events).noneMatch(line -> line.contains("duplicateClassBy"));
  }

  @Test
  void noTestIsTakenForFlaky() {
    assertThat(events)
        .filteredOn(line -> line.contains("\"reason\":\"run-finished\""))
        .singleElement()
        .asString()
        .contains("\"flaky\":[]")
        .contains("\"killed\":7");
  }

  /// `ShopTest`'s context names one of its own classes, `ShopTest.Extra`: a test class, so a new
  /// class each run, and Spring, which keeps a context for the worker's life, builds `ShopTest`'s
  /// anew each run. So `prices()` returning null, which only building the context runs, is caught.
  @Test
  void aContextThatNamesATestClassIsBuiltForEachMutant() {
    assertThat(mutant("prices()")).contains("\"status\":\"Killed\"");
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
