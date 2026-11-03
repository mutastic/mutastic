package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceViewTest {
  @TempDir Path project;

  private SourceView sources;

  @BeforeEach
  void source() throws Exception {
    var file = project.resolve("src/main/java/com/example/Board.java");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        """
        package com.example;

        public class Board {
          public boolean legal(boolean over, int location) {
            return !over
                && location >= 0
                && location < 9
                && free(location);
          }

          public boolean between(int low, int value, int high) {
            return low < value
                && value < high;
          }

          public int next(int location) {
            int step = location < 4 ? 1 : 2;
            return location < 8 ? location + step : 0;
          }

          private boolean free(int location) {
            return true;
          }

          public boolean slice(int c0, int length) {
            if (c0 < 0 || length < 2) {
              return false;
            }
            return true;
          }
        }
        """);
    sources = new SourceView(project, List.of(project.resolve("src/main/java")));
  }

  @Test
  void findsTheOperatorOnALaterLineOfTheStatement() {
    // javac gives the whole `return` line 5; the `<` is on line 7.
    var place = sources.place(boundary("legal", 5, "IF_ICMPGE", "IF_ICMPGT"));

    assertThat(place).isEqualTo(new SourceView.Place(7, 21, 22));
    assertThat(sources.line(boundary("legal", 5, "IF_ICMPGE", "IF_ICMPGT")))
        .contains("&& location < 9");
  }

  @Test
  void twoLikeOperatorsInTheStatementMeanTheWholeStatement() {
    var place = sources.place(boundary("between", 12, "IF_ICMPGE", "IF_ICMPGT"));

    assertThat(place)
        .isEqualTo(new SourceView.Place(12, 5, 23)); // `return low < value`, javac's line
  }

  @Test
  void neverLooksPastTheEndOfTheStatement() {
    // Line 17 ends its statement, so line 18's `<` isn't taken for it.
    assertThat(sources.place(boundary("next", 17, "IF_ICMPGE", "IF_ICMPGT")))
        .isEqualTo(new SourceView.Place(17, 25, 26));
  }

  @Test
  void readsEachJumpTheWayTheSourceHasIt() {
    // `c0 < 0 || …`: javac jumps into the body when the first test holds, so IFLT is `<` as
    // written. `length < 2`: the usual jump past it, IF_ICMPGE, inverted to `<`.
    var first =
        new Mutant(
            "com/example/Board",
            "slice",
            "(II)Z",
            0,
            26,
            BuiltIn.BOUNDARY,
            "IFLT",
            "IFLE",
            Optional.empty(),
            List.of(),
            List.of("c0", "0"));
    var second =
        new Mutant(
            "com/example/Board",
            "slice",
            "(II)Z",
            1,
            26,
            BuiltIn.BOUNDARY,
            "IF_ICMPGE",
            "IF_ICMPGT",
            Optional.empty(),
            List.of(),
            List.of("length", "2"));

    assertThat(sources.advice(first).change()).isEqualTo("`<` becomes `<=`");
    assertThat(sources.advice(second).change()).isEqualTo("`<` becomes `<=`");
    assertThat(sources.place(first))
        .isEqualTo(new SourceView.Place(26, 5, 32)); // `<` occurs twice: the statement
    assertThat(sources.mutatedLine(first, sources.advice(first))).isEmpty(); // so no rewrite either
  }

  private static Mutant boundary(String method, int line, String original, String replacement) {
    return new Mutant(
        "com/example/Board",
        method,
        "(ZI)Z",
        0,
        line,
        BuiltIn.BOUNDARY,
        original,
        replacement,
        Optional.empty());
  }
}
