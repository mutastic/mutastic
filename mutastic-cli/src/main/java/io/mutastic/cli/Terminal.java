package io.mutastic.cli;

import java.util.Map;

/// What mutastic needs to know about where its output goes.
///
/// @param interactive whether a person is likely watching: stdout is a terminal
/// @param env the environment, for `NO_COLOR`, `FORCE_COLOR`, `TERM` and `MUTASTIC_AGENT`
record Terminal(boolean interactive, Map<String, String> env) {
  // Since JDK 22 System.console() is there even when output is redirected, so isTerminal() is what
  // answers. The null check stays: -Djdk.console can still leave no console at all.
  @SuppressWarnings("SystemConsoleNull")
  static Terminal current() {
    var console = System.console();
    return new Terminal(console != null && console.isTerminal(), System.getenv());
  }

  /// `--color=auto`: on a terminal, unless `NO_COLOR` is set or `TERM=dumb`; `FORCE_COLOR` turns it
  /// on anywhere.
  boolean colour(String choice) {
    return switch (choice) {
      case "always" -> true;
      case "never" -> false;
      default ->
          env.containsKey("FORCE_COLOR")
              || (interactive && !env.containsKey("NO_COLOR") && !"dumb".equals(env.get("TERM")));
    };
  }

  /// `--hyperlinks=auto`: file locations become links where a person is reading a terminal that
  /// isn't `TERM=dumb`; terminals that don't know links show the text.
  boolean links(String choice) {
    return switch (choice) {
      case "always" -> true;
      case "never" -> false;
      default -> interactive && !"dumb".equals(env.get("TERM"));
    };
  }

  /// `MUTASTIC_FORMAT` if set; else `agent` when an agent says so (`MUTASTIC_AGENT=1`); else `full`
  /// for people at a terminal and `concise` for logs.
  String defaultFormat() {
    var chosen = env.get("MUTASTIC_FORMAT");
    if (chosen != null && !chosen.isBlank()) {
      return chosen;
    }
    return "1".equals(env.get("MUTASTIC_AGENT")) ? "agent" : interactive ? "full" : "concise";
  }

  /// `MUTASTIC_VOCABULARY`, `plain` unless set.
  String defaultVocabulary() {
    return env.getOrDefault("MUTASTIC_VOCABULARY", "plain");
  }

  /// `MUTASTIC_ARID=audit`: run what the arid filters skip anyway, and say what happened to it.
  boolean auditArid() {
    return "audit".equals(env.get("MUTASTIC_ARID"));
  }

  /// `MUTASTIC_EXPERIMENTAL=ghosts`: what's experimental that every run turns on, by comma.
  java.util.Set<String> experimental() {
    var chosen = env.getOrDefault("MUTASTIC_EXPERIMENTAL", "");
    return java.util.Arrays.stream(chosen.split(",", -1))
        .map(String::strip)
        .filter(word -> !word.isEmpty())
        .collect(java.util.stream.Collectors.toSet());
  }

  /// `MUTASTIC_PILLS`, how labels are drawn: `square` unless set, `rounded` for terminals with a
  /// Nerd Font, or `off` for no blocks at all.
  String defaultLabels() {
    return env.getOrDefault("MUTASTIC_PILLS", "square");
  }
}
