package io.mutastic.cli;

/// How wide a line is on a terminal, and the line cut to fit one: its characters, less its colour
/// and links. `PROGRESS.md` cuts every live line that's too wide, so none wraps.
final class Fit {
  private Fit() {}

  private static final char ESC = '\u001b';
  private static final char BEL = '\u0007';
  private static final String RESET = "\u001b[0m";

  /// The columns `text` takes: each character but those of its escape sequences, a colour's (`ESC [
  /// … m`) or a link's (`ESC ] … ESC \`).
  static int width(String text) {
    int width = 0;
    int at = 0;
    while (at < text.length()) {
      int character = text.codePointAt(at);
      at += Character.charCount(character);
      if (character != ESC) {
        width++;
      } else if (at < text.length()) {
        char kind = text.charAt(at++);
        at = kind == '[' ? afterCsi(text, at) : kind == ']' ? afterOsc(text, at) : at;
      }
    }
    return width;
  }

  /// Where a sequence's parameters end, after its final byte.
  private static int afterCsi(String text, int from) {
    int at = from;
    while (at < text.length()) {
      char character = text.charAt(at++);
      if (character >= '@' && character <= '~') {
        break;
      }
    }
    return at;
  }

  /// Where an operating system command ends, after its BEL or its `ESC \`.
  private static int afterOsc(String text, int from) {
    int at = from;
    while (at < text.length()) {
      char character = text.charAt(at++);
      if (character == BEL) {
        break;
      }
      if (character == ESC && at < text.length()) {
        at++;
        break;
      }
    }
    return at;
  }

  /// `text` cut to `columns`, ending in `…` when cut, with its colour closed.
  static String cut(String text, int columns) {
    if (width(text) <= columns) {
      return text;
    }
    var out = new StringBuilder();
    int shown = 0;
    int at = 0;
    while (at < text.length()) {
      int character = text.codePointAt(at);
      if (character == ESC) {
        at = copyEscape(text, at, out);
        continue;
      }
      if (shown + 1 >= columns) {
        break;
      }
      out.appendCodePoint(character);
      shown++;
      at += Character.charCount(character);
    }
    out.append('…');
    return text.indexOf(ESC) >= 0 ? out.append(RESET).toString() : out.toString();
  }

  /// The escape sequence at `from` copied to `out`, to its final byte; where it ends.
  private static int copyEscape(String text, int from, StringBuilder out) {
    out.append(ESC);
    int at = from + 1;
    while (at < text.length()) {
      char character = text.charAt(at++);
      out.append(character);
      if (character >= '@' && character <= '~' && character != '[') {
        break;
      }
    }
    return at;
  }
}
