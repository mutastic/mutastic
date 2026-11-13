package io.mutastic.cli;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/// How progress is shown, from `--progress`, else `MUTASTIC_PROGRESS`, else where it goes: see
/// `PROGRESS.md`. Who is watching changes only progress, never the format of the result.
final class ProgressChoice {
  private ProgressChoice() {}

  static final List<String> CHOICES = List.of("auto", "bar", "counter", "none");

  /// Variables a CI service sets, besides `CI`. Some CI pretends to be a terminal.
  private static final List<String> CI =
      List.of(
          "TF_BUILD",
          "GITHUB_ACTIONS",
          "GITLAB_CI",
          "BUILDKITE",
          "JENKINS_URL",
          "TEAMCITY_VERSION",
          "CIRCLECI");

  /// Variables a coding agent sets, besides `MUTASTIC_AGENT=1`.
  private static final List<String> AGENTS =
      List.of(
          "CLAUDECODE",
          "CLAUDE_CODE",
          "GEMINI_CLI",
          "CODEX_SANDBOX",
          "CODEX_THREAD_ID",
          "OPENCODE",
          "COPILOT_AGENT",
          "COPILOT_CLI",
          "CURSOR_AGENT",
          "REPL_ID",
          "JUNIE_DATA",
          "GOOSE_PROVIDER",
          "AUGMENT_AGENT");

  /// The mode: as chosen, by option or environment; else counter lines for the agent and JSON Lines
  /// formats, whose readers' harnesses stop a process quiet for too long; else `auto`.
  static Progress.Mode mode(Optional<String> given, String format, Terminal terminal) {
    var chosen = given.or(() -> fromEnvironment(terminal.env())).orElse("");
    return switch (chosen) {
      case "bar" -> Progress.Mode.BAR;
      case "counter" -> Progress.Mode.COUNTER;
      case "none" -> Progress.Mode.NONE;
      case "auto" -> auto(terminal);
      default ->
          format.equals("agent") || format.equals("jsonl") ? Progress.Mode.COUNTER : auto(terminal);
    };
  }

  /// `auto`: in place on a terminal a person is watching; counter lines anywhere else.
  static Progress.Mode auto(Terminal terminal) {
    var env = terminal.env();
    boolean watched =
        terminal.stderrIsTerminal() && !"dumb".equals(env.get("TERM")) && !ci(env) && !agent(env);
    return watched ? Progress.Mode.BAR : Progress.Mode.COUNTER;
  }

  /// `CI` set to anything but `false` or `0`, or a CI service's own variable.
  static boolean ci(Map<String, String> env) {
    var ci = env.get("CI");
    return (ci != null && !ci.equals("false") && !ci.equals("0"))
        || CI.stream().anyMatch(env::containsKey);
  }

  /// Whether the terminal draws progress in its tab or taskbar (OSC 9;4), from what it says of
  /// itself: Windows Terminal, ConEmu, Ghostty and WezTerm do. Others ignore the sequence, but some
  /// take `ESC ] 9` for a notification, so it's sent only where it's known.
  /// `MUTASTIC_TERM_PROGRESS` (or Cargo's `CARGO_TERM_PROGRESS_TERM_INTEGRATION`), `true` or
  /// `false`, decides instead.
  static boolean tab(Map<String, String> env) {
    var asked =
        Optional.ofNullable(env.get("MUTASTIC_TERM_PROGRESS"))
            .or(() -> Optional.ofNullable(env.get("CARGO_TERM_PROGRESS_TERM_INTEGRATION")));
    if (asked.isPresent()) {
      return List.of("true", "1", "always").contains(asked.get());
    }
    return env.containsKey("WT_SESSION")
        || "ON".equals(env.get("ConEmuANSI"))
        || "xterm-ghostty".equals(env.get("TERM"))
        || List.of("ghostty", "WezTerm").contains(env.getOrDefault("TERM_PROGRAM", ""));
  }

  /// `MUTASTIC_AGENT=1`, or a coding agent's own variable.
  static boolean agent(Map<String, String> env) {
    return "1".equals(env.get("MUTASTIC_AGENT")) || AGENTS.stream().anyMatch(env::containsKey);
  }

  private static Optional<String> fromEnvironment(Map<String, String> env) {
    var chosen = Optional.ofNullable(env.get("MUTASTIC_PROGRESS")).filter(set -> !set.isBlank());
    chosen
        .filter(choice -> !CHOICES.contains(choice))
        .ifPresent(
            choice -> {
              throw new Main.ConfigException(
                  "unknown progress %s in MUTASTIC_PROGRESS (one of %s)"
                      .formatted(choice, String.join(", ", CHOICES)));
            });
    return chosen;
  }
}
