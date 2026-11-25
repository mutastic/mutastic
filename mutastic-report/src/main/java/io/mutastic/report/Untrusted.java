package io.mutastic.report;

import io.mutastic.output.Escape;
import io.mutastic.output.Fence;
import java.util.List;

/// Text from the project, as mutastic shows it: escaped, cut short, and for an agent fenced off
/// from mutastic's own words, as the Rust edition's `untrusted` module does it. The rules are the
/// output kit's ([Escape], [Fence]); this is mutastic's use of them: its fence's name, and what a
/// mutant's id looks like.
///
/// Events (`--format=jsonl`) keep the text as it is: JSON escapes it, and it's data by nature.
public final class Untrusted {
  /// The most characters (code points) of one value or line that are shown.
  public static final int LONGEST = Escape.LONGEST;

  /// mutastic's fence: `<<mutastic-data 3f9a0c1e>>`.
  private static final Fence FENCE = new Fence("mutastic-data");

  private Untrusted() {}

  /// Whether `codePoint` is one [#escape] shows as an escape.
  public static boolean hidden(int codePoint) {
    return Escape.hidden(codePoint);
  }

  /// `text` with every control character but a tab, and every invisible format character, shown as
  /// an escape: [Escape#escape].
  public static String escape(String text) {
    return Escape.escape(text);
  }

  /// `text` [#escape]d a line at a time, its lines kept: for output that comes in lines, a build's.
  public static String escapeLines(String text) {
    return Escape.escapeLines(text);
  }

  /// `text` cut to its first [#LONGEST] characters, with how many more there were: `…(1200 more
  /// chars)`.
  public static String cut(String text) {
    return Escape.cut(text);
  }

  /// One value or line of the project's, as it's shown: [#cut], then [#escape]d.
  public static String value(String text) {
    return Escape.value(text);
  }

  /// Values of the project's, test names say, as they're shown: each [#value]d, with commas
  /// between.
  public static String values(List<String> texts) {
    return Escape.values(texts);
  }

  /// Whether `text` is a mutant's id: `m` and 8 hex digits, `m855c8e3c`.
  public static boolean isId(String text) {
    return text.matches("m[0-9a-f]{8}");
  }

  /// The marker that opens a fence: `<<mutastic-data 3f9a0c1e>>`.
  public static String opening(String token) {
    return FENCE.opening(token);
  }

  /// The marker that closes it: `<<end mutastic-data 3f9a0c1e>>`.
  public static String closing(String token) {
    return FENCE.closing(token);
  }

  /// `content`, lines of the project's text already [#escape]d, in mutastic's fence:
  /// [Fence#around(String)].
  public static String fence(String content) {
    return FENCE.around(content);
  }

  /// [#fence] with a token given.
  static String fenced(String content, String token) {
    return FENCE.around(content, token);
  }

  /// The Markdown fence for a block of `code`: [Fence#forCode].
  public static String codeFence(String code) {
    return Fence.forCode(code);
  }

  /// The run's token, for a test.
  static String token() {
    return Fence.token();
  }
}
