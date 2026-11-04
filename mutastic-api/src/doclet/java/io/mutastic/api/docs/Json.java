package io.mutastic.api.docs;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/// JSON for the model: maps, lists and strings, indented two spaces so a change reads as a diff.
final class Json {
  private Json() {}

  static String write(Object value) {
    return write(value, "");
  }

  private static String write(Object value, String indent) {
    var inner = indent + "  ";
    return switch (value) {
      case Map<?, ?> map when map.isEmpty() -> "{}";
      case Map<?, ?> map ->
          map.entrySet().stream()
              .map(
                  entry ->
                      inner
                          + string(entry.getKey().toString())
                          + ": "
                          + write(entry.getValue(), inner))
              .collect(Collectors.joining(",\n", "{\n", "\n" + indent + "}"));
      case Collection<?> list when list.isEmpty() -> "[]";
      case Collection<?> list ->
          list.stream()
              .map(item -> inner + write(item, inner))
              .collect(Collectors.joining(",\n", "[\n", "\n" + indent + "]"));
      default -> string(value.toString());
    };
  }

  private static String string(String text) {
    var out = new StringBuilder("\"");
    for (int i = 0; i < text.length(); i++) {
      char character = text.charAt(i);
      switch (character) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\t' -> out.append("\\t");
        case '\r' -> out.append("\\r");
        default -> {
          if (character < 0x20) {
            out.append("\\u%04x".formatted((int) character));
          } else {
            out.append(character);
          }
        }
      }
    }
    return out.append('"').toString();
  }
}
