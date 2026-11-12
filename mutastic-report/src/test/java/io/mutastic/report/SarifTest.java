package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The SARIF report: a result for each missed and each not-covered mutant, which code scanning
/// shows on the line, keyed by the mutant's id.
class SarifTest {
  private static final String PRICING =
      """
      package com.example;

      public class Pricing {
        public int discount(int total) {
          if (total >= 100) {
            return total / 10;
          }
          return 0;
        }
      }
      """;

  @TempDir Path work;

  @Test
  void itsAReportTheSchemaAccepts() throws Exception {
    assertThat(Schemas.sarifProblems(Json.write(sarif(project("pricing"))))).isEmpty();
    assertThat(Schemas.sarifProblems("{\"version\": \"2.0.0\", \"runs\": [{}]}"))
        .as("the schema refuses what isn't SARIF 2.1.0")
        .isNotEmpty();
  }

  @Test
  void eachMissedAndNotCoveredMutantIsAResultWithItsId() throws Exception {
    var results = results(sarif(project("pricing")));

    assertThat(results)
        .extracting(result -> result.get("ruleId"), result -> result.get("level"))
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("mutastic/boundary", "warning"),
            org.assertj.core.groups.Tuple.tuple("mutastic/return-value", "note"));
    var missed = results.getFirst();
    assertThat(missed.get("partialFingerprints"))
        .isEqualTo(Map.of(Sarif.FINGERPRINT, boundary().id()));
    assertThat(missed).doesNotContainKey("fixes");
    assertThat(missed.get("message"))
        .isEqualTo(
            Map.of(
                "text", "no test checks the boundary of >= in discount()",
                "markdown", "no test checks the boundary of `>=` in `discount()`"));
    var physical = physical(missed);
    // `>=` at columns 15 and 16 of `    if (total >= 100) {`: 1-based, the end one past it.
    assertThat(physical.get("region"))
        .isEqualTo(Map.of("startLine", 5, "startColumn", 15, "endColumn", 17));
    assertThat(physical.get("artifactLocation"))
        .isEqualTo(
            Map.of("uri", "src/main/java/com/example/Pricing.java", "uriBaseId", "%SRCROOT%"));
  }

  @Test
  void eachRuleIsAnOperatorThatMadeAResult() throws Exception {
    var document = sarif(project("pricing"));
    @SuppressWarnings("unchecked")
    var run = ((List<Map<String, Object>>) document.get("runs")).getFirst();
    @SuppressWarnings("unchecked")
    var driver = (Map<String, Object>) ((Map<String, Object>) run.get("tool")).get("driver");
    @SuppressWarnings("unchecked")
    var rules = (List<Map<String, Object>>) driver.get("rules");

    assertThat(rules)
        .extracting(rule -> rule.get("id"))
        .containsExactly("mutastic/boundary", "mutastic/return-value");
    for (var result : results(document)) {
      assertThat(rules.get((int) result.get("ruleIndex")).get("id"))
          .isEqualTo(result.get("ruleId"));
    }
  }

  /// Code scanning reads a path from the repository's root, where the project may be a folder down.
  @Test
  void aPathIsFromTheRepositorysRoot() throws Exception {
    Files.createDirectories(work.resolve(".git"));
    var physical = physical(results(sarif(project("services/pricing"))).getFirst());

    assertThat(physical.get("artifactLocation"))
        .isEqualTo(
            Map.of(
                "uri",
                "services/pricing/src/main/java/com/example/Pricing.java",
                "uriBaseId",
                "%SRCROOT%"));
  }

  private Path project(String folder) throws Exception {
    var project = work.resolve(folder);
    var file = project.resolve("src/main/java/com/example/Pricing.java");
    Files.createDirectories(file.getParent());
    Files.writeString(file, PRICING);
    return project;
  }

  private static Map<String, Object> sarif(Path project) {
    var sources = new SourceView(project, List.of(project.resolve("src/main/java")));
    return new Sarif(project, sources, "1.2.3").document(report());
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> results(Map<String, Object> document) {
    var run = ((List<Map<String, Object>>) document.get("runs")).getFirst();
    return (List<Map<String, Object>>) run.get("results");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> physical(Map<String, Object> result) {
    return (Map<String, Object>)
        ((List<Map<String, Object>>) result.get("locations")).getFirst().get("physicalLocation");
  }

  private static Mutant boundary() {
    return mutant(0, BuiltIn.BOUNDARY, "IF_ICMPLT", "IF_ICMPLE", Optional.empty());
  }

  private static Mutant mutant(
      int ordinal, BuiltIn operator, String original, String replacement, Optional<String> skip) {
    return new Mutant(
        "com/example/Pricing",
        "discount",
        "(I)I",
        ordinal,
        5,
        operator,
        original,
        replacement,
        skip);
  }

  /// A missed boundary, a caught division, a skipped return and one no test reaches.
  private static Report report() {
    var tests = List.of("com.example.PricingTest#bigOrders");
    return new Report(
        "pricing",
        17,
        List.of(
            result(boundary(), Status.MISSED, tests, Optional.empty()),
            result(
                mutant(1, BuiltIn.MATH, "IDIV", "IMUL", Optional.empty()),
                Status.CAUGHT,
                tests,
                Optional.of(tests.getFirst())),
            result(
                mutant(2, BuiltIn.RETURN_VALUE, "IRETURN", "return 1", Optional.of("equivalent")),
                Status.SKIPPED,
                List.of(),
                Optional.empty()),
            result(
                mutant(3, BuiltIn.RETURN_VALUE, "IRETURN", "return 1", Optional.empty()),
                Status.NOT_COVERED,
                List.of(),
                Optional.empty())),
        2,
        List.of(),
        Duration.ofMillis(100),
        1,
        1,
        Duration.ofMillis(300));
  }

  private static MutantResult result(
      Mutant mutant, Status status, List<String> covered, Optional<String> caughtBy) {
    return new MutantResult(
        mutant,
        status,
        covered,
        caughtBy,
        caughtBy.map(ignored -> "org.opentest4j.AssertionFailedError"),
        caughtBy.map(ignored -> "assertion"),
        1,
        Optional.empty(),
        Map.of(),
        Optional.empty(),
        Duration.ofMillis(3));
  }
}
