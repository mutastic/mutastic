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
  }

  @Test
  void noMutantsIsACountOfNone() {
    var list = new MutantList(new SourceView(project, List.of()));

    assertThat(list.of(List.of())).isEqualTo("0 mutants\n");
  }
}
