package io.mutastic.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/// An annotation's arguments as written, split at the top level: `value = {…}`, or a value alone.
/// Its comments are already gone ([SourceTokens]).
record AnnotationArguments(List<String> positional, Map<String, String> named) {
  private static final Pattern ASSIGNMENT =
      Pattern.compile("^\\s*(?<name>[A-Za-z_]\\w*)\\s*=(?!=)(?<value>[\\s\\S]*)$");

  /// `@ValueSource`'s attributes, each an array of values.
  private static final Set<String> VALUE_ARRAYS =
      Set.of(
          "shorts",
          "bytes",
          "ints",
          "longs",
          "floats",
          "doubles",
          "chars",
          "booleans",
          "strings",
          "classes");

  static AnnotationArguments of(String arguments) {
    var positional = new ArrayList<String>();
    var named = new LinkedHashMap<String, String>();
    for (var part : topLevel(arguments)) {
      var assignment = ASSIGNMENT.matcher(part);
      if (assignment.matches()) {
        named.put(assignment.group("name"), assignment.group("value").strip());
      } else if (!part.isBlank()) {
        positional.add(part.strip());
      }
    }
    return new AnnotationArguments(positional, named);
  }

  Optional<String> named(String name) {
    return Optional.ofNullable(named.get(name));
  }

  /// How many values the attribute `name` holds.
  int elements(String name) {
    return named(name).map(AnnotationArguments::count).orElse(0);
  }

  /// How many values `value` holds, given by name or not: `{1, 2}` and Kotlin's `[1, 2]` are two, a
  /// value alone is one. A `@ValueSource` names its array by its type instead: `ints = {1, 2}`.
  int values() {
    if (named.containsKey("value")) {
      return count(named.get("value"));
    }
    return positional.stream().mapToInt(AnnotationArguments::count).sum()
        + named.entrySet().stream()
            .filter(entry -> VALUE_ARRAYS.contains(entry.getKey()))
            .mapToInt(entry -> count(entry.getValue()))
            .sum();
  }

  /// `{a, b}`, `[a, b]` or `arrayOf(a, b)` is two; `a` is one; `{}` none.
  private static int count(String value) {
    var trimmed = value.strip();
    String inner = null;
    if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
        || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
      inner = trimmed.substring(1, trimmed.length() - 1);
    } else if (trimmed.matches("(?s)\\w*[aA]rrayOf\\(.*\\)")) {
      inner = trimmed.substring(trimmed.indexOf('(') + 1, trimmed.lastIndexOf(')'));
    }
    if (inner == null) {
      return trimmed.isEmpty() ? 0 : 1;
    }
    return (int) topLevel(inner).stream().filter(part -> !part.isBlank()).count();
  }

  /// `text` split at its commas outside brackets and strings.
  private static List<String> topLevel(String text) {
    var parts = new ArrayList<String>();
    int depth = 0;
    int start = 0;
    for (int at = 0; at < text.length(); at++) {
      char ch = text.charAt(at);
      if (ch == '"' || ch == '\'') {
        at = endOfString(text, at) - 1;
      } else if ("({[".indexOf(ch) >= 0) {
        depth++;
      } else if (")}]".indexOf(ch) >= 0) {
        depth--;
      } else if (ch == ',' && depth == 0) {
        parts.add(text.substring(start, at));
        start = at + 1;
      }
    }
    parts.add(text.substring(start));
    return parts;
  }

  private static int endOfString(String text, int at) {
    if (text.startsWith("\"\"\"", at)) {
      int end = text.indexOf("\"\"\"", at + 3);
      return end < 0 ? text.length() : end + 3;
    }
    char quote = text.charAt(at);
    int next = at + 1;
    while (next < text.length() && text.charAt(next) != quote) {
      next += text.charAt(next) == '\\' ? 2 : 1;
    }
    return Math.min(text.length(), next + 1);
  }
}
