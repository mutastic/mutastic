package io.proptastic;

import java.util.List;

/// The report under the assertion's message: the header, then each fact, its key right-aligned with
/// the colon after it, so the counterexample's, the longest, is two spaces in. Stacked, when a
/// value spans lines or a line passes the width, each key ends its line and the value goes four
/// spaces in below.
final class Report {
  private Report() {}

  static String block(String header, List<List<String>> facts) {
    int key = facts.stream().mapToInt(fact -> fact.get(0).length()).max().orElse(0);
    int width = width();
    boolean stacked =
        facts.stream()
            .anyMatch(
                fact -> fact.get(1).contains("\n") || 2 + key + 2 + fact.get(1).length() > width);
    var out = new StringBuilder(header);
    for (var fact : facts) {
      out.append('\n');
      if (stacked) {
        out.append("  ").append(fact.get(0)).append(':');
        fact.get(1).lines().forEach(line -> out.append("\n    ").append(line));
      } else {
        out.append("  ")
            .append(" ".repeat(key - fact.get(0).length()))
            .append(fact.get(0))
            .append(": ")
            .append(fact.get(1));
      }
    }
    return out.toString();
  }

  private static int width() {
    try {
      var columns = System.getenv("COLUMNS");
      return columns == null ? 100 : Math.max(40, Integer.parseInt(columns.strip()));
    } catch (NumberFormatException e) {
      return 100;
    }
  }
}
