package io.mutastic.report;

import java.util.Optional;

/// The two sides of a connector on a line of source, `a > 0` and `b < 2` in `if (a > 0 && b < 2)
/// {`: read outward from the connector to the bracket, `?`, `:`, `,`, `;` or `=` that ends the
/// condition, brackets inside a side kept whole.
final class Sides {
  private Sides() {}

  /// The side before the connector at `at`, if there's one.
  static Optional<String> left(String line, int at) {
    int depth = 0;
    int index = at - 1;
    for (; index >= 0; index--) {
      char character = line.charAt(index);
      if (character == ')') {
        depth++;
      } else if (character == '(') {
        if (depth == 0) {
          break;
        }
        depth--;
      } else if (depth == 0 && ends(line, index)) {
        break;
      }
    }
    return side(line.substring(index + 1, at).strip().replaceFirst("^return\\s+", ""));
  }

  /// The side after the connector that ends just before `from`, if there's one.
  static Optional<String> right(String line, int from) {
    int depth = 0;
    int index = from;
    for (; index < line.length(); index++) {
      char character = line.charAt(index);
      if (character == '(') {
        depth++;
      } else if (character == ')') {
        if (depth == 0) {
          break;
        }
        depth--;
      } else if (depth == 0 && ends(line, index)) {
        break;
      }
    }
    return side(line.substring(from, index).strip());
  }

  /// A character that ends a condition: `?`, `:`, `,`, `;`, `{`, a lambda's `->`, or an `=` that
  /// assigns rather than compares.
  private static boolean ends(String line, int index) {
    char character = line.charAt(index);
    if (character == '>' && index > 0 && line.charAt(index - 1) == '-') {
      return true; // a lambda's arrow
    }
    if (character == '=') {
      char before = index > 0 ? line.charAt(index - 1) : ' ';
      char after = index + 1 < line.length() ? line.charAt(index + 1) : ' ';
      return "=!<>".indexOf(before) < 0 && after != '=';
    }
    return "?:,;{".indexOf(character) >= 0;
  }

  private static Optional<String> side(String text) {
    return text.isEmpty() || text.contains("\"") ? Optional.empty() : Optional.of(text);
  }
}
