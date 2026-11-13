package io.mutastic.cli;

/// The parts of a run, in order, as progress names them: see `PROGRESS.md`.
enum Phase {
  READING,
  BUILDING,
  COVERAGE,
  MUTANTS,
  CHECKING;

  /// The column every phase's word sits in, so what follows lines up.
  static final int COLUMN = 10;

  /// `building`.
  String word() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }

  /// The word in its column, padded with spaces.
  String padded() {
    return padded(word());
  }

  /// A phase's past, or any word, in the column, padded with spaces: `built` and five.
  static String padded(String word) {
    return word + " ".repeat(Math.max(1, COLUMN - word.length()));
  }
}
