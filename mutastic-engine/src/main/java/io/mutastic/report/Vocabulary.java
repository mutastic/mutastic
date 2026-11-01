package io.mutastic.report;

/// The words people read for a mutant's fate. `plain` (caught, missed) is the default, because
/// "your tests missed this" needs no metaphor explained; `classic` (killed, survived) is the
/// literature's. Machine formats always use the standard statuses, whatever this says.
public enum Vocabulary {
  PLAIN("caught", "missed", "Missed", "with misses", "Every mutant was caught.", "not covered"),
  CLASSIC("killed", "survived", "Survivors", "with survivors", "Every mutant was killed.", "no coverage");

  final String detected;
  final String undetected;
  final String heading;
  final String classesWith;
  final String allDetected;
  final String notCovered;

  Vocabulary(String detected, String undetected, String heading, String classesWith, String allDetected,
      String notCovered) {
    this.detected = detected;
    this.undetected = undetected;
    this.heading = heading;
    this.classesWith = classesWith;
    this.allDetected = allDetected;
    this.notCovered = notCovered;
  }
}
