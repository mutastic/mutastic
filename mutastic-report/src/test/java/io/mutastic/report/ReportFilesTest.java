package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Operator;
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
            Operator.BOUNDARY,
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
            Operator.MATH,
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
            Operator.RETURN_DEFAULT,
            "IRETURN",
            "return default",
            Optional.of("equivalent: already returns the default"));
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
