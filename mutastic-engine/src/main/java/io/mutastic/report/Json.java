package io.mutastic.report;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/// Just enough JSON for the event stream: maps, lists, strings, numbers, booleans. Keys keep the
/// map's order, so events read the same every run.
final class Json {
  private Json() {}

  static String write(Object value) {
    var out = new StringBuilder();
    write(value, out);
    return out.toString();
  }

  private static void write(Object value, StringBuilder out) {
    switch (value) {
      case null -> out.append("null");
      case Optional<?> optional -> write(optional.orElse(null), out);
      case String text -> string(text, out);
      case Number number -> out.append(number);
      case Boolean flag -> out.append(flag);
      case Map<?, ?> map -> {
        out.append('{');
        boolean first = true;
        for (var entry : map.entrySet()) {
          if (!first) {
            out.append(',');
          }
          first = false;
          string(String.valueOf(entry.getKey()), out);
          out.append(':');
          write(entry.getValue(), out);
        }
        out.append('}');
      }
      case java.util.Collection<?> collection when !(collection instanceof List<?>) ->
          write(List.copyOf(collection), out);
      case List<?> list -> {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
          if (i > 0) {
            out.append(',');
          }
          write(list.get(i), out);
        }
        out.append(']');
      }
      default -> string(value.toString(), out);
    }
  }

  private static void string(String text, StringBuilder out) {
    out.append('"');
    for (char c : text.toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append("\\u%04x".formatted((int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
