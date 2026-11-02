package io.mutastic.cli;

import io.mutastic.cli.Option.Group;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// What `mutastic --help`, `mutastic <command> --help` and a usage error print, written from
/// [Options] and the commands' own descriptions, so help can't offer an option the parser doesn't
/// take, nor leave out one it does.
final class Help {
  private Help() {}

  /// Where the full documentation is.
  static final String DOCS = "https://github.com/mutastic/mutastic#readme";

  private static final int WIDTH = 100;
  private static final int COLUMN = 26;

  /// What each exit status means.
  private static final List<String> EXIT_CODES =
      List.of(
          "0  The score reaches the threshold; for verify, the mutant is caught",
          "1  It doesn't",
          "2  A usage error, or a build mutastic couldn't read",
          "3  The tests fail without any mutant",
          "4  mutastic itself went wrong");

  private static final List<String> ENVIRONMENT =
      List.of(
          "MUTASTIC_FORMAT      The format when --format isn't given",
          "MUTASTIC_AGENT=1     The agent format, for coding agents",
          "MUTASTIC_VOCABULARY  plain or classic, when --vocabulary isn't given",
          "MUTASTIC_PILLS       Labels: square (the default), rounded (for a Nerd Font) or off",
          "MUTASTIC_ARID=audit  Run the mutants the arid filters skip, and say what happened",
          "NO_COLOR             No colour; FORCE_COLOR, colour even off a terminal");

  /// `mutastic --help`: every command, examples, every option, the environment and exit codes.
  static String general(String version) {
    var out = new ArrayList<String>();
    out.add("mutastic %s: finds the changes your tests don't notice.".formatted(version));
    out.addAll(commandsAndExamples());
    for (Group group : Group.values()) {
      var options = Options.ALL.stream().filter(option -> option.group() == group).toList();
      if (group != Group.BUILD_PLUGINS) {
        out.add("");
        out.add(group.heading + ":");
        options.forEach(option -> out.addAll(described(option)));
      }
    }
    out.addAll(section("Environment:", ENVIRONMENT));
    out.addAll(section("Exit codes:", EXIT_CODES));
    out.add("");
    out.add("mutastic <command> --help says more about each. Docs: " + DOCS);
    return String.join("\n", out) + "\n";
  }

  /// Each command with what it does, then a few commands to start from.
  private static List<String> commandsAndExamples() {
    var out = new ArrayList<String>();
    out.add("");
    out.add("Usage:");
    for (Main.Subcommand command : Main.Subcommand.values()) {
      out.add("  " + padded("mutastic " + command.synopsis, 49) + command.summary);
    }
    out.add("");
    out.add("Examples:");
    out.add("  mutastic run");
    out.add("  mutastic run samples/pricing --threshold=70");
    out.add("  mutastic run --since=main");
    out.add("  mutastic verify m855c8e3c");
    return out;
  }

  /// `mutastic run --help`: its usage, what it does, examples and its options.
  static String of(Main.Subcommand command) {
    var out = new ArrayList<String>();
    out.add("Usage: mutastic " + command.synopsis);
    out.add("");
    out.addAll(wrapped(command.summary + ". " + command.detail, WIDTH));
    out.add("");
    out.add("Examples:");
    command.examples.forEach(example -> out.add("  " + example));
    var options = Options.shownFor(command);
    if (!options.isEmpty()) {
      out.add("");
      out.add("Options:");
      options.forEach(option -> out.addAll(described(option)));
    }
    out.addAll(section("Exit codes:", EXIT_CODES));
    out.add("");
    out.add("Docs: " + DOCS);
    return String.join("\n", out) + "\n";
  }

  /// After a usage error: each command's usage, and where to read more.
  static String usage() {
    var out = new ArrayList<String>();
    for (Main.Subcommand command : Main.Subcommand.values()) {
      out.add((out.isEmpty() ? "usage: " : "       ") + "mutastic " + command.synopsis);
    }
    out.add("mutastic --help lists every option.");
    return String.join("\n", out) + "\n";
  }

  /// ` --threshold=N The score…`, wrapped under its description, with the values it takes and the
  /// environment variables that set it.
  private static List<String> described(Option option) {
    var text = new StringBuilder(option.help());
    if (!option.choices().isEmpty()) {
      text.append(" (").append(String.join(", ", option.choices())).append(')');
    }
    if (!option.environment().isEmpty()) {
      text.append(". Also: ").append(option.environment());
    }
    var lines = wrapped(text.toString(), WIDTH - COLUMN);
    var out = new ArrayList<String>();
    var first = "  " + option.synopsis();
    if (first.length() >= COLUMN) {
      out.add(first);
      first = "";
    }
    for (String line : lines) {
      out.add(padded(first, COLUMN) + line);
      first = "";
    }
    return out;
  }

  private static List<String> section(String heading, List<String> lines) {
    var out = new ArrayList<String>();
    out.add("");
    out.add(heading);
    lines.forEach(line -> out.add("  " + line));
    return out;
  }

  /// `text` in lines of at most `width` characters, broken between words.
  static List<String> wrapped(String text, int width) {
    var lines = new ArrayList<String>();
    var line = new StringBuilder();
    for (String word : Arrays.asList(text.split(" ", -1))) {
      if (line.length() > 0 && line.length() + 1 + word.length() > width) {
        lines.add(line.toString());
        line.setLength(0);
      }
      line.append(line.length() > 0 ? " " : "").append(word);
    }
    lines.add(line.toString());
    return lines;
  }

  private static String padded(String text, int width) {
    return text.length() >= width ? text + " " : text + " ".repeat(width - text.length());
  }
}
