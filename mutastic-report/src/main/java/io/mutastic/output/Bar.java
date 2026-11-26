package io.mutastic.output;

/// A progress bar: green [Glyphs#FILLED] to how far it is, on dim [Glyphs#RULE].
public final class Bar {
  private Bar() {}

  /// A bar `columns` wide, filled to `fraction`, from 0 to 1.
  public static String of(double fraction, int columns, boolean colour) {
    var full = (int) Math.min(columns, Math.round(Math.clamp(fraction, 0.0, 1.0) * columns));
    return Paint.paint(colour, Paint.GREEN, Glyphs.FILLED.repeat(full))
        + Paint.paint(colour, Paint.DIM, Glyphs.RULE.repeat(columns - full));
  }
}
