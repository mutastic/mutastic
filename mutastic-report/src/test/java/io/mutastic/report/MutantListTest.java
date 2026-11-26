package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MutantListTest {
  @TempDir Path project;

  @Test
  void eachMutantIsALineThenTheCount() throws Exception {
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
            Optional.empty(),
            List.of(),
            List.of("total", "100"));
    var list = new MutantList(new SourceView(project, List.of(project.resolve("src/main/java"))));

    assertThat(list.of(List.of(boundary)))
        .isEqualTo(
            """
            %s src/main/java/com/example/Pricing.java:5:15 mutastic/boundary >= becomes > in \
            Pricing.discount()
            1 mutants
            """
                .formatted(boundary.id()));
    assertThat(list.of(List.of(boundary), List.of("estimate: a note")))
        .as("a note goes after every mutant's line and before the count")
        .endsWith("Pricing.discount()\nestimate: a note\n1 mutants\n");
  }

  /// A run skips a mutant a tool wrote, so the list leaves it out and doesn't count it; a class
  /// with no source in the roots is named by its own path, a `$` at its start kept.
  @Test
  void generatedMutantsArentListed() {
    var own = mutant("com/example/$Cache", Optional.empty());
    var generated =
        mutant("com/example/$Cache$Definition", Optional.of("generated: @Generated on `size()`"));
    var arid = mutant("com/example/$Cache", Optional.of("arid: a trivial getter"));
    var list = new MutantList(new SourceView(project, List.of()));

    assertThat(list.of(List.of(own, generated, arid)))
        .isEqualTo(
            """
            %s com/example/$Cache.java:3:1 mutastic/return-value returns 0 instead in \
            $Cache.size()
            %s com/example/$Cache.java:3:1 mutastic/return-value returns 0 instead in \
            $Cache.size()
            2 mutants
            """
                .formatted(own.id(), arid.id()));
  }

  private static Mutant mutant(String className, Optional<String> skipped) {
    return new Mutant(
        className,
        "size",
        "()I",
        skipped.map(String::length).orElse(0),
        3,
        BuiltIn.RETURN_VALUE,
        "IRETURN",
        "return 0",
        skipped,
        List.of(),
        List.of());
  }

  @Test
  void noMutantsIsACountOfNone() {
    var list = new MutantList(new SourceView(project, List.of()));

    assertThat(list.of(List.of())).isEqualTo("0 mutants\n");
  }
}
