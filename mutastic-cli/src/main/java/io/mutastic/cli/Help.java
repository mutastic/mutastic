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

  /// Where the latest release's documentation is.
  static final String DOCS = "https://docs.mutastic.io/";

  /// `0.3.1`: a release, whose docs are its line's, `0.3`.
  private static final java.util.regex.Pattern RELEASE =
      java.util.regex.Pattern.compile("(?<major>\\d+)\\.(?<minor>\\d+)\\.\\d+");

  /// The docs for `version`: a release's own line, `https://docs.mutastic.io/v0.3/`, so an older
  /// install reads what it does; the latest release's for a build from source.
  static String docs(String version) {
    var release = RELEASE.matcher(version);
    return release.matches()
        ? DOCS + "v" + release.group("major") + "." + release.group("minor") + "/"
        : DOCS;
  }

  private static final int WIDTH = 100;
  private static final int COLUMN = 26;

  /// A name and what it means: an exit status, an environment variable.
  ///
  /// @param meaning one line, for `--help`
  /// @param detail more, for the docs' reference; empty when the meaning says it all
  record Entry(String name, String meaning, String detail) {
    Entry(String name, String meaning) {
      this(name, meaning, "");
    }
  }

  /// What each exit status means.
  static final List<Entry> EXIT_CODES =
      List.of(
          new Entry(
              "0",
              "The score reaches the threshold; for verify, the mutant is caught or skipped; for"
                  + " gate check, the task is done"),
          new Entry(
              "1",
              "The score is below the threshold; for verify, the mutant is still missed; for gate"
                  + " check, the task isn't done"),
          new Entry("2", "A usage error, or a build mutastic couldn't read"),
          new Entry(
              "3",
              "The tests fail without any mutant: before the mutants run, or, for a test that"
                  + " caught one, when run again on its own after them"),
          new Entry("4", "mutastic itself went wrong"));

  static final List<Entry> ENVIRONMENT =
      List.of(
          new Entry(
              "MUTASTIC_FORMAT",
              "The format when --format isn't given",
              "For a machine or a CI job that always wants the same format, `concise` say."),
          new Entry(
              "MUTASTIC_AGENT=1",
              "The agent format, for coding agents",
              "Coding agents set it, so mutastic answers in the format they read best."
                  + " `MUTASTIC_FORMAT` still wins."),
          new Entry(
              "MUTASTIC_VOCABULARY",
              "plain or classic, when --vocabulary isn't given",
              "`classic` says killed and survived instead of caught and missed. The JSON report"
                  + " and the event stream always use the standard names."),
          new Entry(
              "MUTASTIC_PILLS",
              "Labels: square (the default), rounded (for a Nerd Font) or off",
              "How labels such as MISSED and PASS are drawn. `rounded` needs a terminal font with"
                  + " Nerd Font glyphs; `off` leaves out the coloured blocks and keeps the words."),
          new Entry(
              "MUTASTIC_ARID=audit",
              "Run the mutants the arid filters skip, and say what happened",
              "Adds an Arid audit row to the summary: how many of those mutants ran, and how many"
                  + " the tests caught."),
          new Entry(
              "MUTASTIC_GITHUB=off",
              "No GitHub Actions annotations or job summary",
              "On GitHub Actions (`GITHUB_ACTIONS=true`), a full run annotates the line of each of"
                  + " its first ten misses and adds a summary to the job's page. `off` leaves them"
                  + " out."),
          new Entry(
              "NO_COLOR",
              "No colour",
              "Set to anything, it turns colour off, as it does for many tools."),
          new Entry(
              "FORCE_COLOR",
              "Colour even off a terminal",
              "Set to anything, it turns colour on where mutastic can't tell a person is reading,"
                  + " such as a CI log that shows colour."));

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
    out.add("mutastic <command> --help says more about each. Docs: " + docs(version));
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
  static String of(Main.Subcommand command, String version) {
    var out = new ArrayList<String>();
    out.add("Usage: mutastic " + command.synopsis);
    out.add("");
    out.addAll(wrapped(command.summary + ". " + command.detail, WIDTH));
    out.add("");
    out.addAll(wrapped("Exits " + command.exits, WIDTH));
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
    out.add("Docs: " + docs(version) + "reference/" + command.page() + "/");
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

  private static List<String> section(String heading, List<Entry> entries) {
    int width = entries.stream().mapToInt(entry -> entry.name().length()).max().orElse(0) + 2;
    var out = new ArrayList<String>();
    out.add("");
    out.add(heading);
    entries.forEach(entry -> out.add("  " + padded(entry.name(), width) + entry.meaning()));
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
