package io.mutastic.report;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/// Where a statement is in a source file, from the line javac gives part of it: javac gives a
/// statement the line it starts on, and a call on a later line that line, so a statement split over
/// lines can start before the line a mutant has and go on after it.
final class Statements {
  /// The longest statement looked through, either side of javac's line.
  static final int MOST_LINES = 12;

  /// A Kotlin line that goes on to the next: it ends in an operator, an opening bracket or a comma.
  private static final Pattern KOTLIN_GOES_ON =
      Pattern.compile("(?:[(\\[,=+\\-*/.:]|&&|\\|\\||\\?:|->)$");

  /// A Kotlin line that carries on the one before: it starts with a member, an operator or a
  /// closing bracket.
  private static final Pattern KOTLIN_CARRIES_ON =
      Pattern.compile("^(?:\\.|\\?\\.|&&|\\|\\||\\?:|\\)|])");

  private Statements() {}

  /// The statement's first line, back from `line` past every line that doesn't end the one before.
  static int start(List<String> all, int line, boolean kotlin) {
    int first = line;
    while (first > 1
        && line - first < MOST_LINES
        && goesOn(all.get(first - 2), all.get(first - 1), kotlin)) {
      first--;
    }
    return first;
  }

  /// The statement's last line, on from `line` to the one that ends it.
  static int end(List<String> all, int line, boolean kotlin) {
    int last = line;
    while (last < all.size()
        && last - line < MOST_LINES
        && goesOn(all.get(last - 1), all.get(last), kotlin)) {
      last++;
    }
    return last;
  }

  /// Whether the statement on `line` goes on to `next`.
  private static boolean goesOn(String line, String next, boolean kotlin) {
    var code = code(line);
    if (code.isEmpty() || code(next).isEmpty() || next.strip().startsWith("@")) {
      return false;
    }
    if (kotlin) {
      return KOTLIN_GOES_ON.matcher(code).find() || KOTLIN_CARRIES_ON.matcher(code(next)).find();
    }
    return !endsStatement(code) && !code.startsWith("@");
  }

  /// A line's code, stripped: without a comment, or empty for a line that's only one.
  private static String code(String line) {
    var stripped = line.strip();
    if (stripped.startsWith("*") || stripped.startsWith("/*")) {
      return "";
    }
    return Code.masked(line).strip();
  }

  /// Whether a line of Java ends a statement or opens or closes a block, or is a `case` label: the
  /// statement can't run past it.
  static boolean endsStatement(String line) {
    var end = Code.masked(line).stripTrailing();
    return end.isEmpty()
        || end.endsWith(";")
        || end.endsWith("{")
        || end.endsWith("}")
        || (end.endsWith(":") && end.strip().matches("(?:case\\b.*|default|\\w+)\\s*:"));
  }

  /// The statement's text, with a change made, as lines: none when the change takes it out, and
  /// without a line the change leaves blank.
  static List<String> linesOf(String text, List<String> before) {
    if (text.isEmpty()) {
      return List.of();
    }
    var lines = new ArrayList<String>();
    var after = text.split("\n", -1);
    for (int at = 0; at < after.length; at++) {
      boolean wasBlank = at < before.size() && before.get(at).isBlank();
      if (!after[at].isBlank() || wasBlank) {
        lines.add(after[at]);
      }
    }
    return lines;
  }
}
