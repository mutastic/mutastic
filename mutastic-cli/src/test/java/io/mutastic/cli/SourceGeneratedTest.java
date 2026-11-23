package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A class a generator wrote and checked in, marked with the JDK's `@Generated`, which javac keeps
/// for the source alone: its mutants are skipped as generated, the hand-written ones still run.
class SourceGeneratedTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");

  @TempDir Path work;

  @Test
  void aClassMarkedOnlyInItsSourceIsSkippedAsGenerated() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    Files.writeString(
        project.resolve("src/main/java/com/example/pricing/Quote.java"),
        """
        package com.example.pricing;

        import javax.annotation.processing.Generated;

        @Generated("org.openapitools.codegen.languages.JavaClientCodegen")
        public class Quote {
          private int amount;

          public int getAmount() {
            return amount > 0 ? amount : 0;
          }
        }
        """);

    var run = Samples.mutastic("run", project.toString(), "--format=jsonl", "--report=none");

    assertThat(run.err()).doesNotContain("mutastic:");
    var mutants = run.out().lines().filter(line -> line.contains("mutant-finished")).toList();
    assertThat(mutants)
        .filteredOn(line -> line.contains("com.example.pricing.Quote\""))
        .isNotEmpty()
        .allMatch(
            line ->
                line.contains(
                    "\"statusReason\":\"generated: @Generated on `Quote` in the source\""));
    assertThat(mutants)
        .filteredOn(line -> line.contains("com.example.pricing.Pricing\""))
        .isNotEmpty()
        .noneMatch(line -> line.contains("in the source"));
  }
}
