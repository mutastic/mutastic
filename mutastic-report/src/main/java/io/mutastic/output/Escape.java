package io.mutastic.output;

import java.util.List;
import java.util.stream.Collectors;

/// Text from the project under test, as it's shown: escaped and cut short.
///
/// Anything that can get text into a project (a string literal or a comment, a test's name, a value
/// a test used, a file someone vendored) can write a line that reads as an instruction, or hide
/// one: a bidi control reorders what a terminal shows (Trojan Source), a zero-width character or a
/// tag character carries text nobody sees, and an ANSI escape rewrites the screen. So every value
/// and line of the project's that's printed goes through here, and for an agent, through [Fence]
/// too.
///
/// - [#escape] shows each control character (C0 and C1, tab apart, and so every ANSI escape) as
///   `\x1b`, and each invisible format character (bidi controls, zero-width characters, tag
///   characters, line and paragraph separators, variation selectors, every Unicode `Cf`) as a
///   backslash, `u` and its 4 hex digits, or past `ffff` its hex digits in braces (`u{e0041}`).
/// - [#value] cuts a value or a line longer than [#LONGEST] characters, with how many more there
///   were, `…(1200 more chars)`, then escapes it.
///
/// Events keep the text as it is: JSON escapes it, and it's data by nature.
public final class Escape {
  /// The most characters (code points) of one value or line that are shown.
  public static final int LONGEST = 200;

  /// The invisible format characters [#escape] shows, as ranges of code points: Unicode's `Cf`
  /// (16.0), the line and paragraph separators, the Hangul fillers and the variation selectors.
  /// Fixed here, not taken from the JDK's tables, so both editions escape the same characters.
  private static final int[][] INVISIBLE = {
    {0x00AD, 0x00AD},
    {0x034F, 0x034F},
    {0x0600, 0x0605},
    {0x061C, 0x061C},
    {0x06DD, 0x06DD},
    {0x070F, 0x070F},
    {0x0890, 0x0891},
    {0x08E2, 0x08E2},
    {0x115F, 0x1160},
    {0x17B4, 0x17B5},
    {0x180B, 0x180F},
    {0x200B, 0x200F},
    {0x2028, 0x202E},
    {0x2060, 0x2064},
    {0x2066, 0x206F},
    {0x3164, 0x3164},
    {0xFE00, 0xFE0F},
    {0xFEFF, 0xFEFF},
    {0xFFA0, 0xFFA0},
    {0xFFF9, 0xFFFB},
    {0x110BD, 0x110BD},
    {0x110CD, 0x110CD},
    {0x13430, 0x1343F},
    {0x1BCA0, 0x1BCA3},
    {0x1D173, 0x1D17A},
    {0xE0001, 0xE0001},
    {0xE0020, 0xE007F},
    {0xE0100, 0xE01EF},
  };

  private Escape() {}

  /// Whether `codePoint` is one [#escape] shows as an escape.
  public static boolean hidden(int codePoint) {
    if (Character.isISOControl(codePoint)) {
      return codePoint != '\t';
    }
    for (int[] range : INVISIBLE) {
      if (codePoint >= range[0] && codePoint <= range[1]) {
        return true;
      }
    }
    return false;
  }

  /// `text` with every control character but a tab shown as `\x1b`, and every invisible format
  /// character as a backslash, `u` and its 4 hex digits (in braces past `ffff`). The rest, a
  /// backslash included, is as it was.
  public static String escape(String text) {
    if (text.codePoints().noneMatch(Escape::hidden)) {
      return text;
    }
    var shown = new StringBuilder(text.length() + 8);
    text.codePoints()
        .forEach(
            codePoint -> {
              if (!hidden(codePoint)) {
                shown.appendCodePoint(codePoint);
              } else if (Character.isISOControl(codePoint)) {
                shown.append("\\x%02x".formatted(codePoint));
              } else if (codePoint <= 0xFFFF) {
                shown.append("\\u%04x".formatted(codePoint));
              } else {
                shown.append("\\u{%x}".formatted(codePoint));
              }
            });
    return shown.toString();
  }

  /// `text` [#escape]d a line at a time, its lines kept: for output that comes in lines, a build's.
  public static String escapeLines(String text) {
    return text.lines().map(Escape::escape).collect(Collectors.joining("\n"))
        + (text.endsWith("\n") ? "\n" : "");
  }

  /// `text` cut to its first [#LONGEST] characters, with how many more there were: `…(1200 more
  /// chars)`.
  public static String cut(String text) {
    int count = text.codePointCount(0, text.length());
    if (count <= LONGEST) {
      return text;
    }
    int at = text.offsetByCodePoints(0, LONGEST);
    return "%s…(%d more chars)".formatted(text.substring(0, at), count - LONGEST);
  }

  /// One value or line of the project's, as it's shown: [#cut], then [#escape]d.
  public static String value(String text) {
    return escape(cut(text));
  }

  /// Values of the project's, test names say, as they're shown: each [#value]d, with commas
  /// between.
  public static String values(List<String> texts) {
    return texts.stream().map(Escape::value).collect(Collectors.joining(", "));
  }
}
