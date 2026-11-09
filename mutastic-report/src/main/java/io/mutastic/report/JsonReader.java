package io.mutastic.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/// Just enough JSON to read back what [Json] writes: objects, in their order, arrays, strings,
/// numbers (a whole one as a `Long`, any other as a `Double`) and booleans. A `null` is left out,
/// as a field that isn't there: the events write one only for a value there's none of.
final class JsonReader {
  private final String text;
  private int at;

  private JsonReader(String text) {
    this.text = text;
  }

  /// The value `text` holds, all of it.
  static Object read(String text) {
    var reader = new JsonReader(text);
    var value = reader.value();
    reader.space();
    if (value == null || reader.at != text.length()) {
      throw reader.error("one value");
    }
    return value;
  }

  private @Nullable Object value() {
    space();
    if (at >= text.length()) {
      throw error("a value");
    }
    return switch (text.charAt(at)) {
      case '{' -> object();
      case '[' -> array();
      case '"' -> string();
      case 't' -> word("true", true);
      case 'f' -> word("false", false);
      case 'n' -> word("null", null);
      default -> number();
    };
  }

  private Map<String, Object> object() {
    var fields = new LinkedHashMap<String, Object>();
    at++;
    space();
    if (take('}')) {
      return fields;
    }
    do {
      space();
      if (at >= text.length() || text.charAt(at) != '"') {
        throw error("a field's name");
      }
      var name = string();
      space();
      expect(':');
      var value = value();
      if (value != null) {
        fields.put(name, value);
      }
      space();
    } while (take(','));
    expect('}');
    return fields;
  }

  private List<Object> array() {
    var items = new ArrayList<Object>();
    at++;
    space();
    if (take(']')) {
      return items;
    }
    do {
      var value = value();
      if (value != null) {
        items.add(value);
      }
      space();
    } while (take(','));
    expect(']');
    return items;
  }

  private String string() {
    var out = new StringBuilder();
    at++;
    while (at < text.length() && text.charAt(at) != '"') {
      char character = text.charAt(at++);
      out.append(character == '\\' ? escaped() : character);
    }
    expect('"');
    return out.toString();
  }

  /// The character after a backslash stands for.
  private char escaped() {
    if (at >= text.length()) {
      throw error("an escape");
    }
    char code = text.charAt(at++);
    return switch (code) {
      case 'n' -> '\n';
      case 'r' -> '\r';
      case 't' -> '\t';
      case 'b' -> '\b';
      case 'f' -> '\f';
      case 'u' -> unicode();
      default -> code; // `\"`, `\\` and `\/` stand for themselves
    };
  }

  private char unicode() {
    if (at + 4 > text.length()) {
      throw error("four hex digits");
    }
    var character = (char) Integer.parseInt(text.substring(at, at + 4), 16);
    at += 4;
    return character;
  }

  private Object number() {
    int start = at;
    while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
      at++;
    }
    var written = text.substring(start, at);
    try {
      if (written.matches("-?[0-9]+")) {
        return Long.parseLong(written);
      }
      return Double.parseDouble(written);
    } catch (NumberFormatException e) {
      throw error("a value");
    }
  }

  private @Nullable Object word(String word, @Nullable Object value) {
    if (!text.startsWith(word, at)) {
      throw error("a value");
    }
    at += word.length();
    return value;
  }

  private void space() {
    while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
      at++;
    }
  }

  private boolean take(char character) {
    if (at < text.length() && text.charAt(at) == character) {
      at++;
      return true;
    }
    return false;
  }

  private void expect(char character) {
    if (!take(character)) {
      throw error("'" + character + "'");
    }
  }

  private IllegalArgumentException error(String expected) {
    return new IllegalArgumentException("expected %s at %d".formatted(expected, at));
  }
}
