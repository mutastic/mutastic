package io.mutastic.output;

/// The colour roles, as the terminal's SGR codes, and text painted in one. Both editions use these
/// codes, code for code, and every colour has a glyph or a word beside it ([Glyphs]).
public final class Paint {
  private Paint() {}

  /// What passed or is done.
  public static final String GREEN = "32";

  /// What failed: only its glyph, label and caret.
  public static final String RED = "1;31";

  /// What wasn't reached, or needs a look.
  public static final String AMBER = "33";

  /// What's slow: amber, in bold.
  public static final String SLOW = "1;33";

  /// A heading, or what to do next.
  public static final String BLUE = "1;34";

  /// A name to copy, an id say.
  public static final String CYAN = "36";

  public static final String BOLD = "1";

  /// What's said by the way.
  public static final String DIM = "2";

  /// `text` in `code`, then the colour reset, when `colour` is on; else `text` as it is.
  public static String paint(boolean colour, String code, String text) {
    return colour ? "\u001b[" + code + "m" + text + "\u001b[0m" : text;
  }
}
