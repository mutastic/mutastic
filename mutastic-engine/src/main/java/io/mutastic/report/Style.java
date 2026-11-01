package io.mutastic.report;

/// Colour for people reading a terminal, and nothing for anyone else. Every colour also has a
/// glyph or word beside it, so the output reads the same without colour, for colour-blind
/// readers, and when copied.
///
/// Watermelon colours, used sparingly: green for caught, red only on a missed mutant's glyph,
/// label and caret, never a whole card.
public final class Style {
  public static final Style PLAIN = new Style(false);
  public static final Style COLOUR = new Style(true);

  private final boolean colour;

  private Style(boolean colour) {
    this.colour = colour;
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
