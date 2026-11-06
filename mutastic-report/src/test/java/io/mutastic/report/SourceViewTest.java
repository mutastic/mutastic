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
    // The operands say which `<` each is, so each line is rewritten where its own comparison is.
    assertThat(sources.mutatedLine(first, sources.advice(first)))
        .hasValue("if (c0 <= 0 || length < 2) {");
    assertThat(sources.mutatedLine(second, sources.advice(second)))
        .hasValue("if (c0 < 0 || length <= 2) {");
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

  /// A file of its own, so each line's number is plain to see.
  private Mutant constantOn(String line, String label) throws Exception {
    var file = project.resolve("src/main/java/com/example/Numbers.java");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "package com.example;\n" + line + "\n");
    return new Mutant(
        "com/example/Numbers",
        "run",
        "()I",
        0,
        2,
        BuiltIn.CONSTANT_VALUE,
        "BIPUSH",
        label,
        Optional.empty());
  }

  private Optional<String> mutatedLine(Mutant mutant) {
    return sources.mutatedLine(mutant, sources.advice(mutant));
  }

  @Test
  void aConstantsLineChangesWhereTheNumberIsOnItOnce() throws Exception {
    assertThat(mutatedLine(constantOn("return x >= 100;", "constant 100 to 101")))
        .contains("return x >= 101;");
  }

  @Test
  void aConstantTheSourceNamesLeavesTheLineAlone() throws Exception {
    assertThat(mutatedLine(constantOn("return x >= LIMIT;", "constant 100 to 101"))).isEmpty();
  }

  @Test
  void aConstantThatsOnlyInAStringLeavesTheLineAlone() throws Exception {
    assertThat(mutatedLine(constantOn("return f(\"100\", LIMIT);", "constant 100 to 101")))
        .isEmpty();
    assertThat(mutatedLine(constantOn("return f(\"a\", 100);", "constant 100 to 101")))
        .contains("return f(\"a\", 101);");
  }

  @Test
  void aConstantThereTwiceLeavesTheLineAlone() throws Exception {
    assertThat(mutatedLine(constantOn("return f(100, 100);", "constant 100 to 101"))).isEmpty();
  }

  @Test
  void aLetterIsSaidAsTheSourceHasIt() throws Exception {
    var letter = constantOn("return c == 'a';", "constant 97 to 98");
    assertThat(sources.advice(letter).change()).isEqualTo("the constant `'a'` becomes `'b'`");
    assertThat(mutatedLine(letter)).contains("return c == 'b';");
  }

  @Test
  void onlyALetterOnTheLineIsSaidAsOne() throws Exception {
    assertThat(sources.advice(constantOn("return c == 97;", "constant 97 to 98")).change())
        .isEqualTo("the constant `97` becomes `98`");
    assertThat(sources.advice(constantOn("return c == 'a';", "constant 97 to 0")).change())
        .isEqualTo("the constant `'a'` becomes `0`");
    assertThat(sources.advice(constantOn("return c == ' ';", "constant 32 to 31")).change())
        .isEqualTo("the constant `' '` becomes `31`");
    assertThat(sources.advice(constantOn("return c == '~';", "constant 126 to 127")).change())
        .isEqualTo("the constant `'~'` becomes `127`");
  }
}
