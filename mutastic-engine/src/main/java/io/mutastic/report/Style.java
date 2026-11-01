package io.mutastic.report;

/// Colour for people reading a terminal, and nothing for anyone else. Every colour also has a
/// glyph or word beside it, so the output reads the same without colour, for colour-blind
/// readers, and when copied.
///
/// Watermelon colours, used sparingly: green for caught, red only on a missed mutant's glyph,
/// label and caret, never a whole card.
public final class Style {
  public static final Style PLAIN = new Style(false, false);
  public static final Style COLOUR = new Style(true, false);

  private final boolean colour;
  private final boolean links;

  private Style(boolean colour, boolean links) {
    this.colour = colour;
    this.links = links;
  }

  /// The same style, with file locations as terminal hyperlinks (OSC 8): terminals that know
  /// them open the file, and others show the text.
  public Style withLinks() {
    return new Style(colour, true);
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
