package io.mutastic.cli;

import java.util.Map;

/// What mutastic needs to know about where its output goes.
///
/// @param interactive whether a person is likely watching: stdout is a terminal
/// @param env the environment, for `NO_COLOR`, `FORCE_COLOR`, `TERM` and `MUTASTIC_AGENT`
record Terminal(boolean interactive, Map<String, String> env) {
  static Terminal current() {
    // Since JDK 22 System.console() is there even when output is redirected: ask it.
    var console = System.console();
    return new Terminal(console != null && console.isTerminal(), System.getenv());
  }

  /// `--color=auto`: on a terminal, unless `NO_COLOR` is set or `TERM=dumb`; `FORCE_COLOR`
  /// turns it on anywhere.
  boolean colour(String choice) {
    return switch (choice) {
      case "always" -> true;
      case "never" -> false;
      default -> env.containsKey("FORCE_COLOR")
          || interactive && !env.containsKey("NO_COLOR") && !"dumb".equals(env.get("TERM"));
    };
  }

  /// `--hyperlinks=auto`: file locations become links where a person is reading a terminal
  /// that isn't `TERM=dumb`; terminals that don't know links show the text.
  boolean links(String choice) {
    return switch (choice) {
      case "always" -> true;
      case "never" -> false;
      default -> interactive && !"dumb".equals(env.get("TERM"));
    };
  }

  /// `full` for people, `concise` for logs, `agent` when an agent says so.
  String defaultFormat() {
    return "1".equals(env.get("MUTASTIC_AGENT")) ? "agent" : interactive ? "full" : "concise";
  }
}
