package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportFilesTest {
  @TempDir Path project;

  private ReportFiles files;

  @BeforeEach
  void source() throws Exception {
    var file = project.resolve("src/main/java/com/example/Pricing.java");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
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
        """);
    files =
        new ReportFiles(
            project,
            new SourceView(project, List.of(project.resolve("src/main/java"))),
            80,
            "1.2.3");
  }

  @Test
  void theSameRunWritesTheSameText() {
    var text = Json.write(files.json(report()));

    assertThat(text)
        .contains("\"thresholds\":{\"high\":80,\"low\":60}")
        .contains("\"start\":{\"line\":5,\"column\":15}")
        .contains("\"framework\":{\"name\":\"mutastic\",\"version\":\"1.2.3\"}");
  }

  @Test
  void jsonHasTheSchemasShape() {
    var json = files.json(report());

    assertThat(json)
        .containsKeys("schemaVersion", "thresholds", "files")
        .containsEntry("schemaVersion", "2");
    assertThat(json.get("thresholds")).isEqualTo(Map.of("high", 80, "low", 60));
    @SuppressWarnings("unchecked")
    var file =
        (Map<String, Object>)
            ((Map<String, Object>) json.get("files")).get("src/main/java/com/example/Pricing.java");
    assertThat(file).containsEntry("language", "java").containsKeys("source", "mutants");
    @SuppressWarnings("unchecked")
    var mutants = (List<Map<String, Object>>) file.get("mutants");
    assertThat(mutants.getFirst())
        .containsEntry("status", "Survived")
        .containsEntry("mutatorName", "boundary")
        .containsEntry("replacement", ">")
        // `>=` at columns 15 and 16 of `    if (total >= 100) {`: 1-based, end exclusive.
        .containsEntry(
            "location",
            Map.of(
                "start", Map.of("line", 5, "column", 15), "end", Map.of("line", 5, "column", 17)));
    assertThat(mutants.get(1))
        .containsEntry("status", "Killed")
        .containsEntry("killedBy", List.of("com.example.PricingTest#a<b"));
  }

  @Test
  void xmlParsesAndEscapes() throws Exception {
    var xml = files.xml(report());

    var document =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    var mutations = document.getElementsByTagName("mutation");
    assertThat(mutations.getLength()).isEqualTo(2); // the skipped one isn't listed
    var survived = (org.w3c.dom.Element) mutations.item(0);
    assertThat(survived.getAttribute("status")).isEqualTo("SURVIVED");
    assertThat(survived.getAttribute("detected")).isEqualTo("false");
    assertThat(survived.getElementsByTagName("mutatedClass").item(0).getTextContent())
        .isEqualTo("com.example.Pricing");
    assertThat(survived.getElementsByTagName("sourceFile").item(0).getTextContent())
        .isEqualTo("Pricing.java");
    var killed = (org.w3c.dom.Element) mutations.item(1);
    assertThat(killed.getElementsByTagName("killingTest").item(0).getTextContent())
        .isEqualTo("com.example.PricingTest#a<b");
  }

  @Test
  void xmlNamesEachOperatorAndStatusAsPitDoes() throws Exception {
    var results =
        List.of(
            result(at(BuiltIn.BOUNDARY, "(I)I"), Status.TIMED_OUT),
            result(at(BuiltIn.OTHER_COMPARISON, "(I)I"), Status.NOT_COVERED),
            result(at(BuiltIn.ARITHMETIC_REPLACEMENT, "(I)I"), Status.CAUGHT),
            result(at(BuiltIn.REMOVE_CONDITIONAL, "(I)I"), Status.MISSED),
            result(at(BuiltIn.REMOVE_VOID_CALL, "(I)V"), Status.MISSED),
            result(at(BuiltIn.RETURN_VALUE, "(I)Z"), Status.MISSED),
            result(at(BuiltIn.RETURN_VALUE, "(I)J"), Status.MISSED),
            result(at(BuiltIn.RETURN_VALUE, "(I)Ljava/lang/String;"), Status.MISSED),
            result(at(BuiltIn.RETURN_VALUE, "(I)Ljava/lang/Object;"), Status.MISSED),
            result(at(BuiltIn.RETURN_VALUE, "(I)Z", "return true"), Status.MISSED));
    var report =
        new Report(
            "pricing",
            17,
            results,
            8,
            List.of(),
            Duration.ofMillis(100),
            1,
            1,
            Duration.ofMillis(300));

    var mutations =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(files.xml(report).getBytes(StandardCharsets.UTF_8)))
            .getElementsByTagName("mutation");

    var seen = new java.util.ArrayList<String>();
    for (int i = 0; i < mutations.getLength(); i++) {
      var mutation = (org.w3c.dom.Element) mutations.item(i);
      var mutator = mutation.getElementsByTagName("mutator").item(0).getTextContent();
      seen.add(
          mutation.getAttribute("status")
              + " "
              + mutator.substring(mutator.lastIndexOf("mutators.") + "mutators.".length()));
    }
    assertThat(seen)
        .containsExactly(
            "TIMED_OUT ConditionalsBoundaryMutator",
            "NO_COVERAGE NegateConditionalsMutator",
            "KILLED MathMutator",
            "SURVIVED RemoveConditionalMutator",
            "SURVIVED VoidMethodCallMutator",
            "SURVIVED returns.BooleanFalseReturnValsMutator",
            "SURVIVED returns.PrimitiveReturnsMutator",
            "SURVIVED returns.EmptyObjectReturnValsMutator",
            "SURVIVED returns.NullReturnValsMutator",
            "SURVIVED returns.BooleanTrueReturnValsMutator");
  }

  @Test
  void anUnknownKindIsRefused() {
    assertThatThrownBy(() -> files.write("pdf", report(), project.resolve("out.pdf")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unknown report pdf");
  }

  @Test
  void htmlEmbedsTheViewerAndTheDataSafely() {
    var html = files.html(report());

    assertThat(html).contains("<mutation-test-report-app titlePostfix=\"mutastic\">");
    assertThat(html).contains("MutationTestElements"); // the viewer itself, so it opens offline
    // Only the page's own three script tags close: nothing in the data or viewer ends one early.
    assertThat(html.split("</script>", -1)).hasSize(3);
    assertThat(html).doesNotContain("src=\"http");
  }

  @Test
  void writesWhereAsked() throws Exception {
    var out = project.resolve("reports/out.json");

    files.write("json", report(), out);

    assertThat(Files.readString(out)).startsWith("{\"$schema\":");
  }

  private static Report report() {
    var boundary =
        new Mutant(
            "com/example/Pricing",
            "discount",
            "(I)I",
            0,
            5,
            BuiltIn.BOUNDARY,
            "IF_ICMPLT",
            "IF_ICMPLE",
            Optional.empty());
    var math =
        new Mutant(
            "com/example/Pricing",
            "discount",
            "(I)I",
            1,
            6,
            BuiltIn.MATH,
            "IDIV",
            "IMUL",
            Optional.empty());
    var skipped =
        new Mutant(
            "com/example/Pricing",
            "discount",
            "(I)I",
            3,
            8,
            BuiltIn.RETURN_VALUE,
            "IRETURN",
            "return 1",
            Optional.of("equivalent: already returns null"));
    // A test id with a character XML must escape, and a `</` the HTML must keep out of its script.
    var tests = List.of("com.example.PricingTest#a<b", "com.example.PricingTest#c</script>");
    return new Report(
        "pricing",
        17,
        List.of(
            result(boundary, Status.MISSED, tests, Optional.empty()),
            result(math, Status.CAUGHT, tests, Optional.of(tests.getFirst())),
            result(skipped, Status.SKIPPED, List.of(), Optional.empty())),
        2,
        List.of(),
        Duration.ofMillis(100),
        1,
        1,
        Duration.ofMillis(300));
  }

  private static Mutant at(BuiltIn operator, String descriptor) {
    return at(
        operator,
        descriptor,
        switch (descriptor.substring(descriptor.indexOf(')') + 1)) {
          case "Z" -> "return false";
          case "Ljava/lang/String;" -> "return \"\"";
          case "Ljava/lang/Object;" -> "return null";
          default -> "return 0";
        });
  }

  private static Mutant at(BuiltIn operator, String descriptor, String returned) {
    return new Mutant(
        "com/example/Pricing",
        "discount",
        descriptor,
        0,
        5,
        operator,
        switch (operator) {
          case MATH, ARITHMETIC_REPLACEMENT -> "IDIV";
          case REMOVE_VOID_CALL -> "INVOKEVIRTUAL com/example/Pricing.audit(I)V";
          case RETURN_VALUE -> "IRETURN";
          default -> "IF_ICMPLT";
        },
        switch (operator) {
          case MATH, ARITHMETIC_REPLACEMENT -> "IMUL";
          case REMOVE_VOID_CALL -> "remove call";
          case RETURN_VALUE -> returned;
          case REMOVE_CONDITIONAL -> "never jump";
          default -> "IF_ICMPGE";
        },
        Optional.empty());
  }

  private static MutantResult result(Mutant mutant, Status status) {
    var tests = List.of("com.example.PricingTest#a");
    return result(
        mutant,
        status,
        status == Status.NOT_COVERED ? List.of() : tests,
        status == Status.CAUGHT ? Optional.of(tests.getFirst()) : Optional.empty());
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
