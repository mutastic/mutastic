package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Every place JUnit 5, JUnit 4 and TestNG run code from, outside a test as much as in one, with a
/// method of `Sites` of its own for each (`samples/lifecycle-*`): a mutant that makes it throw, or
/// return what the tests check, is caught by a test of the class that runs it, and no other class's
/// tests are said to cover it, unless it runs where no class can be told: before any class starts,
/// as TestNG makes its test classes and JUnit reads a static `@RegisterExtension`, or between
/// classes, as TestNG's suite and `<test>` methods and its `@AfterClass` do. That counts for every
/// test. What a class's setup or teardown runs counts for every test in it.
class LifecycleTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));

  /// One `+ becomes -` mutant per method of `Sites`: `x + 1` and `10 / (x + 1)`.
  private static final Pattern MUTANT =
      Pattern.compile(
          "\"status\":\"(?<status>\\w+)\".*\"method\":\"(?<method>\\w+)\\(.*\"original\":\"\\+\""
              + ".*\"coveredBy\":\\[(?<covered>[^]]*)](?:,\"caughtBy\":\"(?<by>[^\"]+)\")?");

  @TempDir Path work;

  record Found(String method, String status, String coveredBy, String caughtBy) {}

  @Test
  void junit5() throws Exception {
    var found =
        mutants(
            "lifecycle-junit5",
            List.of(
                "beforeAll",
                "afterAll",
                "beforeEach",
                "afterEach",
                "perClass",
                "staticExtension",
                "extendWith",
                "nested",
                "methodSource",
                "parameterized",
                "superclass",
                "staticInit",
                "field",
                "constructor"),
            List.of("staticExtensionValue", "staticExtensionThrows"));
    var needs = "com.example.lifecycle.NeedsAnotherTestSiteTest#needsWhatTheLastLeft";
    // It fails on its own, with no mutant in: so once the mutants have run, what it caught runs
    // again without it, and it covers nothing.
    assertThat(found)
        .filteredOn(mutant -> mutant.method().startsWith("needsAnotherTest"))
        .hasSize(2)
        .allSatisfy(mutant -> assertThat(mutant.coveredBy()).doesNotContain(needs));
    assertThat(statusOf(found, "needsAnotherTestValue")).isEqualTo("Survived");
    assertThat(statusOf(found, "needsAnotherTestThrows")).isEqualTo("Killed");
  }

  @Test
  void junit4() throws Exception {
    mutants(
        "lifecycle-junit4",
        List.of(
            "before",
            "after",
            "beforeClass",
            "afterClass",
            "rule",
            "classRule",
            "staticInit",
            "field",
            "constructor"),
        List.of());
  }

  @Test
  void testng() throws Exception {
    mutants(
        "lifecycle-testng",
        List.of(
            "beforeMethod",
            "afterMethod",
            "beforeClass",
            "afterClass",
            "beforeSuite",
            "afterSuite",
            "beforeTest",
            "afterTest",
            "beforeGroups",
            "dataProvider",
            "staticInit",
            "field",
            "constructor"),
        List.of(
            "afterClass",
            "beforeSuite",
            "afterSuite",
            "beforeTest",
            "afterTest",
            "dataProvider",
            "staticInit",
            "field",
            "constructor"));
  }

  /// The sample's mutants: each of those of `sites` (the start of its methods' names, and of its
  /// test class's) caught by a test of its class, which covers it with all its tests; and those of
  /// `everyTest` (the start of their methods' names) covered by every test, the bystander's too.
  private List<Found> mutants(String sample, List<String> sites, List<String> everyTest)
      throws Exception {
    var project = Samples.copy(SAMPLES.resolve(sample), work.resolve(sample));
    var run =
        Samples.mutastic(
            "run", project.toString(), "--format=jsonl", "--workers=2", "--threshold=0");
    var found =
        run
            .out()
            .lines()
            .map(MUTANT::matcher)
            .filter(java.util.regex.Matcher::find)
            .map(
                match ->
                    new Found(
                        match.group("method"),
                        match.group("status"),
                        match.group("covered"),
                        match.group("by") == null ? "" : match.group("by")))
            // A mutant run again once a test that caught it was found to fail on its own is
            // reported again: its last result is its result.
            .collect(
                java.util.stream.Collectors.toMap(
                    Found::method,
                    mutant -> mutant,
                    (first, last) -> last,
                    java.util.LinkedHashMap::new))
            .values()
            .stream()
            .toList();
    var ofSites =
        sites.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    for (var mutant : found) {
      ofSites.stream()
          .filter(site -> mutant.method().startsWith(site))
          .findFirst()
          .ifPresent(
              site ->
                  assertCaughtInItsClass(
                      mutant, site, everyTest.stream().anyMatch(mutant.method()::startsWith)));
    }
    assertThat(found).as(run.err()).hasSizeGreaterThanOrEqualTo(2 * sites.size());
    return found;
  }

  private static void assertCaughtInItsClass(Found mutant, String site, boolean everyTest) {
    var testClass =
        "com.example.lifecycle." + Character.toUpperCase(site.charAt(0)) + site.substring(1);
    assertThat(mutant.status()).as(mutant.method()).isEqualTo("Killed");
    assertThat(mutant.caughtBy()).as(mutant.method()).startsWith(testClass + "SiteTest");
    assertThat(mutant.coveredBy()).as(mutant.method()).contains(testClass + "SiteTest");
    if (everyTest) {
      assertThat(mutant.coveredBy()).as(mutant.method()).contains("ABystanderTest");
    } else {
      assertThat(mutant.coveredBy()).as(mutant.method()).doesNotContain("ABystanderTest");
    }
  }

  private static String statusOf(List<Found> found, String method) {
    return found.stream()
        .filter(mutant -> mutant.method().equals(method))
        .findFirst()
        .orElseThrow()
        .status();
  }
}
