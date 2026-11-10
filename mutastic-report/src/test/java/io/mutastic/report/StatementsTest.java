package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// How much of a file a card shows: the whole statement around javac's line, and not a line of the
/// statements, comments or labels beside it.
class StatementsTest {
  private static final List<String> JAVA =
      List.of(
          "class Shop {", // 1
          "  @Override", // 2
          "  int total() {", // 3
          "    // the sum", // 4
          "    int sum = price", // 5
          "        + fee", // 6
          "        + tax;", // 7
          "    /* checked */", // 8
          "    switch (sum) {", // 9
          "      case 2:", // 10
          "        sum = sum > 1", // 11
          "            ? 1", // 12
          "            : 2;", // 13
          "      default:", // 14
          "        run(sum)", // 15
          "            .now();", // 16
          "    }", // 17
          "    return sum;", // 18
          "  }", // 19
          "}"); // 20

  @Test
  void aStatementGoesFromWhereItStartsToItsSemicolon() {
    assertThat(Statements.start(JAVA, 6, false)).isEqualTo(5);
    assertThat(Statements.end(JAVA, 6, false)).isEqualTo(7);
    assertThat(Statements.start(JAVA, 13, false)).isEqualTo(11);
    assertThat(Statements.end(JAVA, 11, false)).isEqualTo(13);
    assertThat(Statements.start(JAVA, 16, false)).isEqualTo(15);
  }

  @Test
  void aStatementDoesNotTakeInACommentALabelOrABlock() {
    assertThat(Statements.start(JAVA, 5, false)).as("after a comment").isEqualTo(5);
    assertThat(Statements.start(JAVA, 11, false)).as("after a case").isEqualTo(11);
    assertThat(Statements.start(JAVA, 15, false)).as("after default").isEqualTo(15);
    assertThat(Statements.start(JAVA, 18, false)).as("after a block").isEqualTo(18);
    assertThat(Statements.start(JAVA, 3, false)).as("after an annotation").isEqualTo(3);
    assertThat(Statements.end(JAVA, 3, false)).as("an opened block").isEqualTo(3);
  }

  @Test
  void aStatementStopsBeforeAnAnnotatedLineAndAtAJavadocLine() {
    var lines =
        List.of(
            "    int a = b",
            "    @SuppressWarnings(\"x\") int c = d;",
            "   * doc",
            "    e(f,",
            "    // g",
            "    h);");

    assertThat(Statements.end(lines, 1, false)).isEqualTo(1);
    assertThat(Statements.start(lines, 4, false)).isEqualTo(4);
    assertThat(Statements.end(lines, 4, false)).as("before a comment").isEqualTo(4);
  }

  @Test
  void aStatementOnTheFirstOrLastLineStopsThere() {
    var lines = List.of("a(b,", "  c);");

    assertThat(Statements.start(lines, 2, false)).isEqualTo(1);
    assertThat(Statements.end(lines, 1, false)).isEqualTo(2);
    assertThat(Statements.end(lines, 2, false)).isEqualTo(2);
  }

  @Test
  void aStatementIsLookedThroughOnlySoFarEitherSide() {
    var lines = new ArrayList<String>();
    for (int line = 1; line <= 40; line++) {
      lines.add("    .step" + line + "()");
    }

    assertThat(Statements.start(lines, 20, false)).isEqualTo(20 - Statements.MOST_LINES);
    assertThat(Statements.end(lines, 20, false)).isEqualTo(20 + Statements.MOST_LINES);
  }

  @Test
  void aKotlinStatementGoesOnByAnOperatorOrAMember() {
    var lines =
        List.of(
            "    val total = price +", // 1
            "        fee", // 2
            "    val name = raw", // 3
            "        .trim()", // 4
            "    log(total)", // 5
            "    log(name)"); // 6

    assertThat(Statements.start(lines, 2, true)).isEqualTo(1);
    assertThat(Statements.end(lines, 1, true)).isEqualTo(2);
    assertThat(Statements.start(lines, 4, true)).isEqualTo(3);
    assertThat(Statements.end(lines, 3, true)).isEqualTo(4);
    assertThat(Statements.start(lines, 6, true)).isEqualTo(6);
    assertThat(Statements.end(lines, 5, true)).isEqualTo(5);
    assertThat(Statements.start(lines, 6, false)).as("as Java, nothing ends").isEqualTo(1);
    assertThat(Statements.start(List.of("    // trimmed", "        .trim()"), 2, true))
        .isEqualTo(2);
  }

  @Test
  void aLineEndsAStatementWithASemicolonABraceOrALabel() {
    assertThat(Statements.endsStatement("    x = 1; // done")).isTrue();
    assertThat(Statements.endsStatement("    if (a) {  ")).isTrue();
    assertThat(Statements.endsStatement("    }")).isTrue();
    assertThat(Statements.endsStatement("      case 1:")).isTrue();
    assertThat(Statements.endsStatement("      default:")).isTrue();
    assertThat(Statements.endsStatement("    outer:")).isTrue();
    assertThat(Statements.endsStatement("   ")).isTrue();
    assertThat(Statements.endsStatement("    x = a ? b :")).isFalse();
    assertThat(Statements.endsStatement("        low :")).as("a conditional's arm").isFalse();
    assertThat(Statements.endsStatement("        1 :")).as("a number isn't a label").isFalse();
    assertThat(Statements.endsStatement("      default :")).isTrue();
    assertThat(Statements.endsStatement("    run(\"a;\")")).isFalse();
    assertThat(Statements.endsStatement("    run(a,")).isFalse();
  }

  /// A conditional's arm on a line of its own, `low :`, is part of the statement, not a label.
  @Test
  void aConditionalsArmOnItsOwnLineIsPartOfTheStatement() {
    var lines =
        List.of(
            "    int pick = high > limit ?", // 1
            "        low :", // 2
            "        1 :", // 3
            "        high;"); // 4

    assertThat(Statements.start(lines, 4, false)).isEqualTo(1);
    assertThat(Statements.end(lines, 1, false)).isEqualTo(4);
  }

  /// A line that starts with a comment that closes has code after it; one that doesn't close is
  /// only comment.
  @Test
  void aLineThatStartsWithAClosedCommentHasItsCode() {
    var lines =
        List.of(
            "    int total = price", // 1
            "        /* fee */ + fee;", // 2
            "    /* a note", // 3
            "       on two lines */", // 4
            "    run();"); // 5

    assertThat(Statements.start(lines, 2, false)).isEqualTo(1);
    assertThat(Statements.end(lines, 1, false)).isEqualTo(2);
    assertThat(Statements.start(lines, 5, false)).isEqualTo(5);
  }

  @Test
  void theMutantsLinesLeaveOutOnlyALineTheChangeMadeBlank() {
    assertThat(Statements.linesOf("", List.of("    run();"))).isEmpty();
    assertThat(Statements.linesOf("  a(\n\n  b);", List.of("  a(", "", "  b);")))
        .containsExactly("  a(", "", "  b);");
    assertThat(Statements.linesOf("  a(\n   \n  b);", List.of("  a(", "  x,", "  b);")))
        .containsExactly("  a(", "  b);");
    assertThat(Statements.linesOf("  a(\n  \n  b);", List.of("  a(")))
        .containsExactly("  a(", "  b);");
  }
}
