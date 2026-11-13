package io.mutastic.gradle.plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/// What Gradle's progress line says of a run, from the run's live events: `mutants 2871/5525 · 52%
/// · ✗ 41 missed · ~21m left`. A pure function of each event's line, as `PROGRESS.md` and the
/// events schema describe them.
final class FooterText {
  private FooterText() {}

  /// One of an event's fields: a key, and a string or a number. Events are flat but for
  /// `run-started` and `run-finished`, whose nested fields the footer doesn't read.
  private static final Pattern FIELD =
      Pattern.compile(
          "\"(?<key>[A-Za-z]+)\":(?:\"(?<text>(?:[^\"\\\\]|\\\\.)*)\"|(?<number>-?[0-9][0-9.Ee+-]*))");

  /// What the line says for the event on `line`, if it's one the footer shows: a phase starting, or
  /// how far one is.
  static Optional<String> of(String line) {
    var fields = fields(line);
    var phase = fields.getOrDefault("phase", "");
    return switch (fields.getOrDefault("reason", "")) {
      case "phase-started" -> Optional.of(phase);
      case "progress" -> Optional.of(progress(phase, fields));
      default -> Optional.empty();
    };
  }

  /// Each top-level field's value, as text; the first of a key wins.
  static Map<String, String> fields(String line) {
    var fields = new HashMap<String, String>();
    var matcher = FIELD.matcher(line);
    while (matcher.find()) {
      var text = matcher.group("text");
      fields.putIfAbsent(matcher.group("key"), text != null ? text : matcher.group("number"));
    }
    return fields;
  }

  private static String progress(String phase, Map<String, String> fields) {
    var count = fields.containsKey("total") ? fields.get("done") + "/" + fields.get("total") : "";
    return switch (phase) {
      case "mutants" -> mutants(count, fields);
      case "coverage" -> count.isEmpty() ? phase : phase + " " + count + " tests";
      case "checking" -> count.isEmpty() ? phase : phase + " " + count;
      default -> phase;
    };
  }

  /// `mutants 2871/5525 · 52% · ✗ 41 missed · ~21m left`.
  private static String mutants(String count, Map<String, String> fields) {
    var said = new StringBuilder("mutants ").append(count);
    var weighted = fields.get("weighted");
    if (weighted != null) {
      said.append(" · ").append((long) Math.floor(Double.parseDouble(weighted) * 100)).append('%');
    }
    said.append(" · ✗ ").append(fields.getOrDefault("missed", "0")).append(" missed");
    var left = fields.get("leftMs");
    if (left != null) {
      said.append(" · ").append(left(Long.parseLong(left)));
    }
    return said.toString();
  }

  /// What's left, steadily, as the run's own live line says it: whole minutes above ten, ten
  /// seconds above one, five below.
  static String left(long millis) {
    double seconds = millis / 1000.0;
    if (seconds >= 600) {
      return "~" + Math.round(seconds / 60) + "m left";
    }
    if (seconds >= 60) {
      long tens = Math.round(seconds / 10) * 10;
      return "~%dm%02ds left".formatted(tens / 60, tens % 60);
    }
    return "~" + Math.max(5, Math.round(seconds / 5) * 5) + "s left";
  }
}
