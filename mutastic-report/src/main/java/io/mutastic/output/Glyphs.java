package io.mutastic.output;

/// The glyphs output draws with, each beside a word or a colour, never instead of one, so output
/// reads the same without colour and when copied. Both editions draw the same ones.
public final class Glyphs {
  private Glyphs() {}

  /// Passed: caught, done, all clear.
  public static final String PASS = "✓";

  /// Failed: missed.
  public static final String FAIL = "✗";

  /// Not reached: not covered.
  public static final String OPEN = "○";

  /// Took too long.
  public static final String TIMER = "⏱";

  /// What to look at, or to do, next.
  public static final String POINTER = "❯";

  /// Up and down since the last time.
  public static final String UP = "▲";

  public static final String DOWN = "▼";

  /// A rule across a section, repeated.
  public static final String RULE = "─";

  /// A bar's filled part, repeated; [#RULE] is its empty part.
  public static final String FILLED = "━";

  /// The gutter beside a block of code.
  public static final String GUTTER = "│";

  /// What opens a block of code, before its location.
  public static final String CORNER = "┌─";

  /// What hangs a line under the one above.
  public static final String UNDER = "└";

  /// A spinner's frames, one per redraw.
  public static final String SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";
}
