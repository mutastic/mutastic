package io.mutastic.report;

/// Colour for people reading a terminal, and nothing for anyone else. Every colour also has a
/// glyph or word beside it, so the output reads the same without colour, for colour-blind
/// readers, and when copied.
///
/// Watermelon colours, used sparingly: green for caught, red only on a missed mutant's glyph,
/// label and caret, never a whole card.
public final class Style {
  public static final Style PLAIN = new Style(false, false, false);
  public static final Style COLOUR = new Style(true, false, false);

  /// Nerd Font half-circles, for rounded label ends.
  private static final String ROUND_LEFT = "\ue0b6";
  private static final String ROUND_RIGHT = "\ue0b4";

  /// Label backgrounds.
  static final String RED = "41";
  static final String GREEN = "42";
  static final String CYAN = "46";

  private final boolean colour;
  private final boolean links;
  private final boolean rounded;

  private Style(boolean colour, boolean links, boolean rounded) {
    this.colour = colour;
    this.links = links;
    this.rounded = rounded;
  }

  /// The same style, with file locations as terminal hyperlinks (OSC 8): terminals that know
  /// them open the file, and others show the text.
  public Style withLinks() {
    return new Style(colour, true, rounded);
  }

  /// The same style with rounded label ends (`MUTASTIC_PILLS=rounded`). They need a Nerd Font,
  /// so square is the default: without one, the ends show as boxes.
  public Style withRoundedLabels() {
    return new Style(colour, links, true);
  }

  /// A label, `MISSED` on red: a coloured block in a terminal, `[MISSED]` without colour.
  String label(String text, String background) {
    if (!colour) {
      return "[" + text + "]";
    }
    var body = "\u001b[" + background + ";30;1m " + text + " \u001b[0m";
    if (!rounded) {
      return body;
    }
    var edge = String.valueOf(Integer.parseInt(background) - 10); // 41 (red background) → 31 (red text)
    return paint(edge, ROUND_LEFT) + "\u001b[" + background + ";30;1m" + text + "\u001b[0m" + paint(edge, ROUND_RIGHT);
  }

  /// `text` linking to `file`, when links are on. The line number stays in the text: whether
  /// terminals honour one in a `file:` URI varies.
  String link(java.nio.file.Path file, String text) {
    return links ? "\u001b]8;;" + file.toUri() + "\u001b\\" + text + "\u001b]8;;\u001b\\" : text;
  }

  String red(String text) {
    return paint("1;31", text);
  }

  String green(String text) {
    return paint("32", text);
  }

  String amber(String text) {
    return paint("33", text);
  }

  String blue(String text) {
    return paint("1;34", text);
  }

  String cyan(String text) {
    return paint("36", text);
  }

  String bold(String text) {
    return paint("1", text);
  }

  String dim(String text) {
    return paint("2", text);
  }

  private String paint(String code, String text) {
    return colour ? "\u001b[" + code + "m" + text + "\u001b[0m" : text;
  }
}
